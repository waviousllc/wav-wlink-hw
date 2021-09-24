package wav.wlink

import wav.common._

import chisel3._
import chisel3.util._
import chisel3.stage.ChiselStage
import chisel3.experimental.ChiselEnum


import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.ahb._
import freechips.rocketchip.amba.apb._
import freechips.rocketchip.subsystem.BaseSubsystem
import freechips.rocketchip.subsystem.CrossingWrapper
import freechips.rocketchip.config.{Parameters, Field, Config}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.regmapper._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._
import freechips.rocketchip.unittest._
import freechips.rocketchip.devices.tilelink._




class WavD2DGpioTx(val dataWidth: Int = 8, val padWidth: Int = 1)(implicit p: Parameters) extends Module{
  val io = IO(new Bundle{
    val scan        = new WavScanBundle
    val clk         = Input (Bool())
    val reset       = Input (Bool())
    val clk_en      = Input (Bool())
    val ready       = Output(Bool())
    val link_data   = Input (UInt(dataWidth.W))
    val link_clk    = Output(Bool())
    val pad         = Output(Bool())
    val pad_clk     = Output(Bool())
  })
  
  require(isPow2(dataWidth), "dataWidth is not a power of 2")
  require(padWidth == 1, "padWidth should be 1")
  require(dataWidth > padWidth, "dataWidth is not larger than padWidth")
  
  val divRatio      = dataWidth / padWidth
  val countclog2    = log2Ceil(divRatio)
  val countReset    = "hff".U
  
  io.scan.out       := false.B
  
  val hs_reset_scan = WavResetSync(io.clk, io.reset, io.scan.asyncrst_ctrl)
  
  
  withClockAndReset(io.clk.asClock, hs_reset_scan.asAsyncReset){
    val count_in    = Wire(UInt(countclog2.W))
    val count       = RegNext(count_in, countReset(countclog2-1,0))
    
    //Using active low latch clock gating cell, we want the clock to be enabled/disabled on the -1 cycle
    val clk_en_qual_in  = Wire(Bool())
    val clk_en_qual     = RegNext(clk_en_qual_in, false.B)
    clk_en_qual_in      := Mux(count_in.andR, io.clk_en, clk_en_qual)
    
    val hs_clk_gated  = WavClockGate(io.clk, io.reset, clk_en_qual, io.scan.mode, withDemet=false)
    io.pad_clk      := hs_clk_gated
    
    val tx_pad_array= Wire(Vec(divRatio, UInt(padWidth.W)))
    val tx_pad_in   = Wire(UInt(padWidth.W))
    val tx_pad      = RegNext(tx_pad_in, 0.U)
    
    count_in        := count + 1.U
    
    val link_clk_pre = ~count(countclog2-1)
    io.link_clk      := WavClockMux(io.scan.mode, io.scan.clk, link_clk_pre)
    
    
    for(i <- 0 until divRatio){
      tx_pad_array(i) := io.link_data(((i+1)*padWidth)-1, (i*padWidth))
    }
    tx_pad_in       := tx_pad_array(count)
    
    io.ready        := RegNext(~hs_reset_scan, false.B)   //how should we do this?
    io.pad          := tx_pad_array(count)//tx_pad
    
    
    
  }
  
}


class WavD2DGpioRx(val dataWidth: Int = 8, val padWidth: Int = 1)(implicit p: Parameters) extends Module{
  val io = IO(new Bundle{
    val scan      = new WavScanBundle
    val por_reset = Input (Bool())
    
    val pol       = Input (Bool())
    val link_clk  = Output(Bool())
    val link_data = Output(UInt(dataWidth.W))
    
    val pad_clk   = Input (Bool())
    val pad       = Input (Bool())
  })
  
  require(isPow2(dataWidth), "dataWidth is not a power of 2")
  require(padWidth == 1, "padWidth should be 1")
  require(dataWidth > padWidth, "dataWidth is not larger than padWidth")
  
  val divRatio      = dataWidth / padWidth
  val countclog2    = log2Ceil(divRatio)
  val countReset    = "hff".U
  io.scan.out       := false.B
  
  
  val pad_clk_inv     = WavClockInv(io.pad_clk)
  val pad_clk_scan    = WavClockMux(io.scan.mode, io.scan.clk, io.pad_clk)
  val pad_clk_inv_scan= WavClockMux(io.scan.mode, io.scan.clk, WavClockMux(io.pol, pad_clk_inv, io.pad_clk))
  val por_reset_scan  = WavResetSync(pad_clk_scan, io.por_reset, io.scan.asyncrst_ctrl)
  
  
    
  val count_in      = Wire(UInt(countclog2.W))
  val count         = withClockAndReset(pad_clk_scan.asClock, io.por_reset.asAsyncReset){RegNext(count_in, countReset(countclog2-1,0))}

  count_in          := count + 1.U
  val link_clk_pre  = ~count(countclog2-1)
  //io.link_clk       := WavClockMux(io.scan.mode, io.scan.clk, link_clk_pre)
  io.link_clk       := WavClockMux(io.scan.mode, io.scan.clk, link_clk_pre)


  val link_data_pad_clk_in  = Wire(Vec(dataWidth, Bool()))
  val link_data_pad_clk     = withClockAndReset(pad_clk_inv_scan.asClock, io.por_reset.asAsyncReset){RegNext(link_data_pad_clk_in.asUInt, 0.U)}
  for(i <- 0 until dataWidth){
    when(count === i.asUInt){
      link_data_pad_clk_in(i)  := io.pad
    }.otherwise{
      link_data_pad_clk_in(i)  := link_data_pad_clk(i)
    }
  }

  val link_data_reg_in  = Wire(UInt(dataWidth.W))
  val link_data_reg     = withClockAndReset(io.link_clk.asClock, io.por_reset.asAsyncReset){RegNext(link_data_reg_in, 0.U)}
  link_data_reg_in      := link_data_pad_clk

  io.link_data          := link_data_reg
    
   
  
  
}




class WavD2DGpioBumpBundle(
  val numTxLanes: Int = 1,
  val numRxLanes: Int = 1
) extends Bundle{
  val clk_tx      = Output(Bool())
  val tx          = Output(Vec(numTxLanes, Bool()))
  val clk_rx      = Input (Bool())
  val rx          = Input (Vec(numRxLanes, Bool()))
}

class WavD2DGpio(
  val numTxLanes: Int, 
  val numRxLanes: Int, 
  val dataWidth : Int = 16, 
  val baseAddr  : BigInt = 0x0
)(implicit p: Parameters) extends LazyModule{
  
  
  val device = new SimpleDevice("wavd2dgpio", Seq("wavious,d2dgpio"))
  val node = WavAPBRegisterNode(
    address = AddressSet.misaligned(baseAddr, 0x4),
    device  = device,
    //concurrency = 1, //make depending on apn (apb requires 1)
    beatBytes = 4,
    noRegTest = true) 
  
  lazy val module = new LazyModuleImp(this) with RequireAsyncReset{
    val io = IO(new Bundle{
      val scan        = new WavScanBundle
      val link_tx     = new WlinkPHYTxBundle(numTxLanes * dataWidth)
      val link_rx     = new WlinkPHYRxBundle(numRxLanes * dataWidth)
      val hsclk       = Input (Bool())
      val por_reset   = Input (Bool())
      val pad         = new WavD2DGpioBumpBundle(numTxLanes, numRxLanes)
    })
    io.scan.out := false.B

    val gpiotx        = Seq.tabulate(numTxLanes)(i => Module(new WavD2DGpioTx(dataWidth)))
    val gpiorx        = Seq.tabulate(numRxLanes)(i => Module(new WavD2DGpioRx(dataWidth)))

    val hsclk_scan    = WavClockMux(io.scan.mode, io.scan.clk, io.hsclk)
    val por_reset_scan= WavResetSync(hsclk_scan, io.por_reset, io.scan.asyncrst_ctrl)
    
    val swi_pream_count = Wire(UInt(8.W))
    val swi_post_count  = Wire(UInt(8.W))
    val swi_pol         = Wire(Bool())

    val tx_en         = Wire(Bool())

    for(i <- 0 until numTxLanes){
      gpiotx(i).io.scan.connectScan(io.scan)
      gpiotx(i).io.clk        := hsclk_scan
      gpiotx(i).io.reset      := io.por_reset
      gpiotx(i).io.clk_en     := tx_en
      gpiotx(i).io.link_data  := io.link_tx.tx_link_data((dataWidth*i)+dataWidth-1, dataWidth*i)

      io.pad.tx(i)            := gpiotx(i).io.pad
    }
    //Always just use lane0 for link clock
    io.link_tx.tx_link_clk    := gpiotx(0).io.link_clk
    io.pad.clk_tx             := gpiotx(0).io.pad_clk

    
    val precount_in      = Wire(UInt(8.W))
    val precount         = withClockAndReset(io.link_tx.tx_link_clk.asClock, por_reset_scan.asAsyncReset){RegNext(precount_in, "hf".U)}
    precount_in          := Mux(io.link_tx.tx_en, Mux(precount === 0.U, precount, precount - 1.U), swi_pream_count)
    val postcount_in     = Wire(UInt(8.W))
    val postcount        = withClockAndReset(io.link_tx.tx_link_clk.asClock, por_reset_scan.asAsyncReset){RegNext(postcount_in, 0.U)}
    postcount_in         := Mux(~io.link_tx.tx_en, Mux(postcount === 0.U, postcount, postcount - 1.U), swi_post_count)

    tx_en               := io.link_tx.tx_en | (postcount =/= 0.U && ~io.link_tx.tx_en)
    io.link_tx.tx_ready := precount === 0.U & io.link_tx.tx_en

    val rx_link_data      = Wire(Vec(numRxLanes, UInt(dataWidth.W)))
    for(i <- 0 until numRxLanes){
      gpiorx(i).io.scan.connectScan(io.scan)
      gpiorx(i).io.por_reset  := io.por_reset
      gpiorx(i).io.pol        := swi_pol
      gpiorx(i).io.pad_clk    := io.pad.clk_rx
      gpiorx(i).io.pad        := io.pad.rx(i)
      rx_link_data(i)         := gpiorx(i).io.link_data
    }
    io.link_rx.rx_link_clk    := gpiorx(0).io.link_clk
    io.link_rx.rx_link_data   := rx_link_data.asUInt
    io.link_rx.rx_data_valid  := true.B
    
    
    node.regmap(
      WavSWReg(0x0, "Control", "General Controls for GPIO PHY",
        WavRW(swi_pream_count,    1.U,    "pream_count",      "Number of cycles to send the clock prior to starting data"),
        WavRW(swi_post_count,     7.U,    "post_count",       "Number of cycles to send the clock after sending data"),
        WavRW(swi_pol,            true.B, "polarity",         "Polarity of the RX sampling clock"))        
    )
  }
}


