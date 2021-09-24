package wav.wlink

import wav.common._

//import Chisel._
import chisel3._
import chisel3.util._
import chisel3.experimental.{ChiselEnum}
import chisel3.stage.ChiselStage

//import chisel3.experimental._
import freechips.rocketchip.amba._
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

import chisel3.internal.sourceinfo.SourceInfo
import chisel3.util.random.FibonacciLFSR

import scala.math.max


/**
  *   Parameter object which user will define in config fragments
  */
case class WlinkApbTgtParams(
  base            : BigInt = 0x0,
  size            : BigInt = x"1_0000_0000",
  beatBytes       : Int    = 4,
  name            : String = "apbTgt")

case class WlinkApbIniParams(
  name            : String = "apbIni")



/**
  *   Object to create the APBTgt ports. 
  *   User should pass in the node list. Can use to create ports when you don't have a complete
  *   diplo graph but want to create the design
  */
object WlinkApbTgtCreatePorts{
  def apply(nodes: scala.collection.mutable.Buffer[(APBTgtToWlink, WlinkApbTgtParams, Boolean)])(implicit p: Parameters): Unit = {
    nodes.foreach{ case (node, params, connected) =>
      
      if(!connected){
        //Dummy node
        val apb_ini_node = APBMasterNode(
          portParams = Seq(APBMasterPortParameters(masters = Seq(APBMasterParameters(name = "apbIniToWlink"))))
        )

        node.apb_tgt_node := apb_ini_node

        val apb_tgt = InModuleBody { apb_ini_node.makeIOs()(valName=ValName(params.name + "_tgt")) }
      }
    }
  }
}


/**
  *   APbTgt trait to mixin
  *   This will check for any WlinkApbTgtParams and make the corresponding APBTgtToWlink Nodes
  *   and will connect each channel to the main routers
  */
trait CanHaveAPBTgtPort { this: Wlink =>
  
  private val apbTgtParamsOpt = params.apbTgtParams
  private var index           = 0x0
  
  val apbtgtwlNodes = scala.collection.mutable.Buffer[(APBTgtToWlink, WlinkApbTgtParams, Boolean)]()
  
  apbTgtParamsOpt.map(paramsmap =>
    paramsmap.foreach{apbTgtParams =>
      val apbname = "wlink_" + apbTgtParams.name
      val apbTgt2wl  = LazyModule(new APBTgtToWlink(shortPacketStart  = currentShortPacketIndex,
                                                    longPacketStart   = currentLongPacketIndex,
                                                    address           = AddressSet.misaligned(apbTgtParams.base, apbTgtParams.size), 
                                                    beatBytes         = apbTgtParams.beatBytes, 
                                                    baseAddr          = params.baseAddr + params.apbTgtFCbaseAddr + index,  
                                                    name              = apbname,
                                                    noRegTest         = params.noRegTest))
                                               
      val temp = (apbTgt2wl, apbTgtParams, false)
      apbtgtwlNodes += temp
                                                     
      txrouter.node             := apbTgt2wl.apbTgtFC.txnode

      apbTgt2wl.apbTgtFC.rxnode := rxrouter.node

      apbTgt2wl.xbar.node       := xbar.node

      
      // Connect the clocks/resets/enables internally
      val connectingClocksEnables = InModuleBody { 
        apbTgt2wl.module.io.app.clk              := app_clk_scan
        apbTgt2wl.module.io.app.reset            := app_clk_reset_scan
        apbTgt2wl.module.io.app.enable           := swi_enable

        apbTgt2wl.module.io.tx.clk               := tx_link_clk
        apbTgt2wl.module.io.tx.reset             := tx_link_clk_reset

        apbTgt2wl.module.io.rx.clk               := rx_link_clk
        apbTgt2wl.module.io.rx.reset             := rx_link_clk_reset
        
        crc_errors(crcErrIndex)                  := apbTgt2wl.module.io.rx.crc_err
        crcErrIndex             += 1
      }
      
      index = index + 0x100
      
      
      currentLongPacketIndex  += 1
      currentShortPacketIndex += 4
    }
  )
  
  //When you don't want to have another node to connect at a higer level such
  //as only generating the wlink itself
  if(p(WlinkOnlyGen)){
    WlinkApbTgtCreatePorts(apbtgtwlNodes)
  }
}



/**
  *   Object to create the APBIni ports. 
  *   User should pass in the node list. Can use to create ports when you don't have a complete
  *   diplo graph but want to create the design
  *
  *   FOR NOW WE WILL JUST ALWAYS CREATE A 4GB Interface
  */
object WlinkApbIniCreatePorts{
  def apply(nodes: scala.collection.mutable.Buffer[(APBIniToWlink, WlinkApbIniParams, Boolean)])(implicit p: Parameters): Unit = {
    nodes.foreach{ case (node, params, connected) =>
      
      if(!connected){
        val apb_tgt_node = APBSlaveNode(Seq(APBSlavePortParameters(
          Seq(APBSlaveParameters(
            address       = AddressSet.misaligned(0x0, x"1_0000_0000"),
            supportsRead  = true,
            supportsWrite = true)),
          beatBytes  = 4)))

        apb_tgt_node := node.apb_ini_node

        val apb_ini = InModuleBody { apb_tgt_node.makeIOs()(valName=ValName(params.name + "_ini")) }
      }
    }
  }
}

/**
  *   APbIni trait to mixin
  *   This will check for any WlinkApbTgtParams and make the corresponding APBTgtToWlink Nodes
  *   and will connect each channel to the main routers
  */
trait CanHaveAPBIniPort { this: Wlink =>
  
  private val apbIniParamsOpt = params.apbIniParams
  private var index        = 0x0
  
  val apbiniwlNodes = scala.collection.mutable.Buffer[(APBIniToWlink, WlinkApbIniParams, Boolean)]()
  
  apbIniParamsOpt.map(paramsmap =>
    paramsmap.foreach{apbIniParams =>
      val apbname = "wlink_" + apbIniParams.name
      val apbIni2wl  = LazyModule(new APBIniToWlink(shortPacketStart    = currentShortPacketIndex,
                                                    longPacketStart     = currentLongPacketIndex,
                                                    baseAddr            = params.baseAddr + params.apbIniFCbaseAddr + index,  
                                                    name                = apbname,
                                                    noRegTest           = params.noRegTest))
                                               
      val temp = (apbIni2wl, apbIniParams, false)
      apbiniwlNodes += temp
                                                     
      txrouter.node             := apbIni2wl.apbIniFC.txnode

      apbIni2wl.apbIniFC.rxnode := rxrouter.node

      apbIni2wl.xbar.node       := xbar.node

      
      // Connect the clocks/resets/enables internally
      val connectingClocksEnables = InModuleBody { 
        apbIni2wl.module.io.app.clk              := app_clk_scan
        apbIni2wl.module.io.app.reset            := app_clk_reset_scan
        apbIni2wl.module.io.app.enable           := swi_enable

        apbIni2wl.module.io.tx.clk               := tx_link_clk
        apbIni2wl.module.io.tx.reset             := tx_link_clk_reset

        apbIni2wl.module.io.rx.clk               := rx_link_clk
        apbIni2wl.module.io.rx.reset             := rx_link_clk_reset
        
        crc_errors(crcErrIndex)                  := apbIni2wl.module.io.rx.crc_err
        crcErrIndex             += 1
      }
      
      index = index + 0x100
      
      
      currentLongPacketIndex  += 1
      currentShortPacketIndex += 4
    }
  )
  
  //When you don't want to have another node to connect at a higer level such
  //as only generating the wlink itself
  if(p(WlinkOnlyGen)){
    WlinkApbIniCreatePorts(apbiniwlNodes)
  }
}


//====================================================

object APBTgtToWlinkOP extends ChiselEnum{
  val READ  = Value(0.U)
  val WRITE = Value(1.U)
}

/**
  *   Main APB Target to Wlink conversion node
  *
  */
class APBTgtToWlink(
  shortPacketStart  : Int = 0x8,
  longPacketStart   : Int = 0x40,
  address           : Seq[AddressSet],
  addressSize       : Int = 32,             //Forces the address size internally
  beatBytes         : Int = 4,
  baseAddr          : BigInt = 0x0,
  name              : String = "",
  dataDepth         : Int = 4,
  noRegTest         : Boolean = false
)(implicit p: Parameters) extends LazyModule{
  
  // This xbar is for register accesses
  val xbar    = LazyModule(new APBFanout)
  
  val apb_tgt_node = APBSlaveNode(Seq(APBSlavePortParameters(
    Seq(APBSlaveParameters(
      address       = address,
      //resources     = resources,
      //regionType    = if (cacheable) RegionType.UNCACHED else RegionType.IDEMPOTENT,
      //executable    = executable,
      supportsRead  = true,
      supportsWrite = true)),
    beatBytes  = beatBytes)))
  
  val chPrefix = if(name == "") "" else s"${name}_"
  
  
  //addr + data + pstrb + prot + R/W
  val appDataWidth = addressSize + (beatBytes*8) + beatBytes + 3 + 1  
  // Packed in the following: [ADDR, DATA, PSTRB, PROT, R/W] when sending data
  // Data is recieved in the following [R/W, PSLVERR, DATA]
  
  require(beatBytes==4, "Would you kindly try to keep the APB busses to 32bits?")
  
  val apbTgtFC = LazyModule(new WlinkGenericFCSM(baseAddr        = baseAddr + 0x0, 
                                                 a2lDataWidth    = appDataWidth, 
                                                 a2lDepth        = dataDepth, 
                                                 l2aDataWidth    = appDataWidth, 
                                                 l2aDepth        = dataDepth, 
                                                 channelName     = chPrefix + "apbTgtFC", 
                                                 dataIdDefault   = longPacketStart,
                                                 crIdDefault     = shortPacketStart,
                                                 crackIdDefault  = shortPacketStart + 1,
                                                 ackIdDefault    = shortPacketStart + 2,
                                                 nackIdDefault   = shortPacketStart + 3,
                                                 noRegTest       = noRegTest))
      
  

  apbTgtFC.suggestName(name+"apbTgtFC")
  
  apbTgtFC.node   := xbar.node

  
  lazy val module = new LazyModuleImp(this) with RequireAsyncReset{
    
    val io = IO(new Bundle{
      val app = new Bundle{
        val clk         = Input (Bool())
        val reset       = Input (Bool())
        val enable      = Input (Bool())
      }
      
      val tx = new Bundle{
        val clk     = Input (Bool())
        val reset   = Input (Bool())
      }
      
      val rx = new Bundle{
        val clk     = Input (Bool())
        val reset   = Input (Bool())
        val crc_err = Output(Bool())
      }
    })
    
    io.rx.crc_err   := apbTgtFC.module.io.rx.crc_err
    
    //APB Bundle
    val apb_tgt = apb_tgt_node.in.head._1
    
    
    object APBTgtToWlinkState extends ChiselEnum{
      val IDLE      = Value(0.U)
      val APB_STALL = Value(1.U)
      val APB_WRITE = Value(2.U)
      val APB_READ  = Value(3.U)
    }
        
    apbTgtFC.module.io.tx.clk     := io.tx.clk
    apbTgtFC.module.io.tx.reset   := io.tx.reset
    
    apbTgtFC.module.io.rx.clk     := io.rx.clk
    apbTgtFC.module.io.rx.reset   := io.rx.reset
    
    apbTgtFC.module.io.app.clk    := io.app.clk
    apbTgtFC.module.io.app.reset  := io.app.reset
    apbTgtFC.module.io.app.enable := io.app.enable
    
    
    withClockAndReset(io.app.clk.asClock, io.app.reset.asAsyncReset){
      val enable_ff2 = WavDemetReset(io.app.enable)
      
      val nstate      = WireInit(APBTgtToWlinkState.IDLE)
      val state       = RegNext(nstate, APBTgtToWlinkState.IDLE)
      
      val a2l_data    = Wire(UInt(appDataWidth.W))
      val a2l_valid   = Wire(Bool())
      val a2l_ready   = apbTgtFC.module.io.app.a2l_ready
      
      val l2a_accept  = Wire(Bool())
      val l2a_valid   = apbTgtFC.module.io.app.l2a_valid
      val l2a_data    = apbTgtFC.module.io.app.l2a_data
      
      apbTgtFC.module.io.app.l2a_accept := l2a_accept
      
      
      val apb_prdata  = Wire(UInt((beatBytes*8).W))
      val apb_pready  = Wire(Bool())
      val apb_pslverr = Wire(Bool())
      
      val write_resp_pkt = l2a_valid & (l2a_data(33) === APBTgtToWlinkOP.WRITE.asUInt.asBool)
      val read_resp_pkt  = l2a_valid & (l2a_data(33) === APBTgtToWlinkOP.READ.asUInt.asBool)
      
      
      nstate              := state
      a2l_data            := 0.U
      a2l_valid           := false.B
      apb_prdata          := 0.U
      apb_pready          := false.B
      apb_pslverr         := false.B
      l2a_accept          := false.B
      
      
      when(state === APBTgtToWlinkState.IDLE){
        //------------------------------------
        when(enable_ff2){
          when(apb_tgt.psel & ~apb_tgt.penable){
            when(apb_tgt.pwrite){
              a2l_data    := Cat(apb_tgt.paddr, apb_tgt.pwdata, apb_tgt.pstrb, apb_tgt.pprot, APBTgtToWlinkOP.WRITE.asUInt.asBool)
              a2l_valid   := true.B
              nstate      := Mux(a2l_ready, APBTgtToWlinkState.APB_WRITE, APBTgtToWlinkState.APB_STALL)
            }.otherwise{
              a2l_data    := Cat(apb_tgt.paddr, apb_tgt.pwdata, apb_tgt.pstrb, apb_tgt.pprot, APBTgtToWlinkOP.READ.asUInt.asBool)
              a2l_valid   := true.B
              nstate      := Mux(a2l_ready, APBTgtToWlinkState.APB_READ, APBTgtToWlinkState.APB_STALL)
            }
          }
        }
      }.elsewhen(state === APBTgtToWlinkState.APB_STALL){
        //------------------------------------
        // If a2l_ready is not asserted just wait here until we are good to go
        
        when(apb_tgt.pwrite){
          a2l_data        := Cat(apb_tgt.paddr, apb_tgt.pwdata, apb_tgt.pstrb, apb_tgt.pprot, APBTgtToWlinkOP.WRITE.asUInt.asBool)
          a2l_valid       := true.B
          nstate          := Mux(a2l_ready, APBTgtToWlinkState.APB_WRITE, APBTgtToWlinkState.APB_STALL)
        }.otherwise{
          a2l_data        := Cat(apb_tgt.paddr, apb_tgt.pwdata, apb_tgt.pstrb, apb_tgt.pprot, APBTgtToWlinkOP.READ.asUInt.asBool)
          a2l_valid       := true.B
          nstate          := Mux(a2l_ready, APBTgtToWlinkState.APB_READ, APBTgtToWlinkState.APB_STALL)
        }
        
      }.elsewhen(state === APBTgtToWlinkState.APB_WRITE){
        //------------------------------------
        when(l2a_valid){
          //when(l2a_valid){
            apb_pready    := true.B
            apb_pslverr   := l2a_data(32)
            l2a_accept    := true.B
            nstate        := APBTgtToWlinkState.IDLE
          //} //COME BACK AND ADD A CHECK FOR BAD PACKET
        }
        
      }.elsewhen(state === APBTgtToWlinkState.APB_READ){
        //------------------------------------
        when(l2a_valid){
          //when(l2a_valid){
            apb_pready    := true.B
            apb_pslverr   := l2a_data(32)
            apb_prdata    := l2a_data(31,0)
            l2a_accept    := true.B
            nstate        := APBTgtToWlinkState.IDLE
          //} //COME BACK AND ADD A CHECK FOR BAD PACKET
        }
      }.otherwise{
        //------------------------------------
        nstate            := APBTgtToWlinkState.IDLE
      }
      
      when(~enable_ff2){
        nstate            := APBTgtToWlinkState.IDLE
      }
      
      
      apb_tgt.pready  := apb_pready
      apb_tgt.pslverr := apb_pslverr
      apb_tgt.prdata  := apb_prdata
      
      apbTgtFC.module.io.app.a2l_data   := a2l_data
      apbTgtFC.module.io.app.a2l_valid  := a2l_valid
      
    }
    
  }
}





/**
  *   Main APB Initiator to Wlink conversion node
  *
  */
class APBIniToWlink(
  shortPacketStart  : Int = 0x8,
  longPacketStart   : Int = 0x40,
  addressSize       : Int = 32,             //Forces the address size internally
  beatBytes         : Int = 4,
  baseAddr          : BigInt = 0x0,
  name              : String = "",
  dataDepth         : Int = 4,
  noRegTest         : Boolean = false
)(implicit p: Parameters) extends LazyModule{
  
  // This xbar is for register accesses
  val xbar    = LazyModule(new APBFanout)
  
  val apb_ini_node = APBMasterNode(
    portParams = Seq(APBMasterPortParameters(masters = Seq(APBMasterParameters(name = "apbIniToWlink"))))
  )
  
  val chPrefix = if(name == "") "" else s"${name}_"
  
  
  //addr + data + pstrb + prot + R/W
  val appDataWidth = addressSize + (beatBytes*8) + beatBytes + 3 + 1  
  // Packed in the following: [ADDR, DATA, PSTRB, PROT, R/W] when sending data
  // Data is recieved in the following [R/W, PSLVERR, DATA]
  
  require(beatBytes==4, "Would you kindly try to keep the APB busses to 32bits?")
  
  val apbIniFC = LazyModule(new WlinkGenericFCSM(baseAddr        = baseAddr + 0x0, 
                                                 a2lDataWidth    = appDataWidth, 
                                                 a2lDepth        = dataDepth, 
                                                 l2aDataWidth    = appDataWidth, 
                                                 l2aDepth        = dataDepth, 
                                                 channelName     = chPrefix + "apbIniFC", 
                                                 dataIdDefault   = longPacketStart,
                                                 crIdDefault     = shortPacketStart,
                                                 crackIdDefault  = shortPacketStart + 1,
                                                 ackIdDefault    = shortPacketStart + 2,
                                                 nackIdDefault   = shortPacketStart + 3,
                                                 noRegTest       = noRegTest))
      
  

  apbIniFC.suggestName(name+"apbIniFC")
  
  apbIniFC.node   := xbar.node

  
  lazy val module = new LazyModuleImp(this) with RequireAsyncReset{
    
    val io = IO(new Bundle{
      val app = new Bundle{
        val clk         = Input (Bool())
        val reset       = Input (Bool())
        val enable      = Input (Bool())
      }
      
      val tx = new Bundle{
        val clk     = Input (Bool())
        val reset   = Input (Bool())
      }
      
      val rx = new Bundle{
        val clk     = Input (Bool())
        val reset   = Input (Bool())
        val crc_err = Output(Bool())
      }
    })
    
    
    io.rx.crc_err   := apbIniFC.module.io.rx.crc_err
    
    //APB Bundle
    val apb_ini = apb_ini_node.out.head._1
    
    
    object APBIniToWlinkState extends ChiselEnum{
      val IDLE      = Value(0.U)
      val APB_WRITE = Value(1.U)
      val APB_READ  = Value(2.U)
      val APB_HOLD  = Value(3.U)
    }
    
    
    
    apbIniFC.module.io.tx.clk     := io.tx.clk
    apbIniFC.module.io.tx.reset   := io.tx.reset
    
    apbIniFC.module.io.rx.clk     := io.rx.clk
    apbIniFC.module.io.rx.reset   := io.rx.reset
    
    apbIniFC.module.io.app.clk    := io.app.clk
    apbIniFC.module.io.app.reset  := io.app.reset
    apbIniFC.module.io.app.enable := io.app.enable
    
    
    withClockAndReset(io.app.clk.asClock, io.app.reset.asAsyncReset){
      val enable_ff2      = WavDemetReset(io.app.enable)
      
      val nstate          = WireInit(APBIniToWlinkState.IDLE)
      val state           = RegNext(nstate, APBIniToWlinkState.IDLE)
      
      val a2l_data        = Wire(UInt(appDataWidth.W))
      val a2l_valid       = Wire(Bool())
      val a2l_ready       = apbIniFC.module.io.app.a2l_ready
      
      val l2a_accept      = Wire(Bool())
      val l2a_valid       = apbIniFC.module.io.app.l2a_valid
      val l2a_data        = apbIniFC.module.io.app.l2a_data
      apbIniFC.module.io.app.l2a_accept := l2a_accept
      
      val apb_psel        = Wire(Bool())
      val apb_penable     = Wire(Bool())
      val apb_pwrite      = Wire(Bool())
      val apb_paddr       = Wire(UInt(addressSize.W))
      val apb_pwdata      = Wire(UInt((beatBytes*8).W))
      val apb_pstrb       = Wire(UInt(beatBytes.W))
      val apb_pprot       = Wire(UInt(3.W))
      
      val apb_pready      = Wire(Bool())
      val apb_pslverr     = Wire(Bool())
      val apb_prdata      = Wire(UInt((beatBytes*8).W))
      
      
      val apb_prdata_reg_in   = Wire(UInt((beatBytes*8).W))
      val apb_prdata_reg      = RegNext(apb_prdata_reg_in, 0.U)
      val apb_pslverr_reg_in  = Wire(Bool())
      val apb_pslverr_reg     = RegNext(apb_pslverr_reg_in, false.B)
      val was_write_in        = Wire(Bool())
      val was_write           = RegNext(was_write_in, false.B)
      
      val write_pkt       = l2a_valid & (l2a_data(0) === APBTgtToWlinkOP.WRITE.asUInt.asBool)
      val read_pkt        = l2a_valid & (l2a_data(0) === APBTgtToWlinkOP.READ.asUInt.asBool)
      
      nstate              := state
      apb_psel            := false.B
      apb_penable         := false.B
      apb_pwrite          := false.B
      apb_paddr           := 0.U
      apb_pwdata          := 0.U
      apb_pstrb           := 0.U
      apb_pprot           := 0.U
      a2l_data            := 0.U
      a2l_valid           := false.B
      l2a_accept          := false.B
      apb_prdata_reg_in   := apb_prdata_reg
      apb_pslverr_reg_in  := apb_pslverr_reg
      was_write_in        := was_write
      
      
      when(state === APBIniToWlinkState.IDLE){
        //------------------------------------
        when(enable_ff2){
          when(l2a_valid){
            when(write_pkt){
              apb_psel          := true.B
              apb_paddr         := l2a_data(71,40)
              apb_pwdata        := l2a_data(39,8)
              apb_pwrite        := true.B
              apb_pprot         := l2a_data(3,1)
              apb_pstrb         := l2a_data(7,4)
              nstate            := APBIniToWlinkState.APB_WRITE
            }.elsewhen(read_pkt){
              apb_psel          := true.B
              apb_paddr         := l2a_data(71,40)
              apb_pprot         := l2a_data(3,1)
              apb_pstrb         := l2a_data(7,4)
              nstate            := APBIniToWlinkState.APB_READ
            }
          }
        }
      }.elsewhen(state === APBIniToWlinkState.APB_WRITE){
        //------------------------------------
        apb_psel                := true.B
        apb_paddr               := l2a_data(71,40)
        apb_pwdata              := l2a_data(39,8)
        apb_pwrite              := true.B
        apb_penable             := true.B
        was_write_in            := true.B
        
        when(apb_pready){
          l2a_accept            := true.B
          when(a2l_ready){
            a2l_data            := Cat(APBTgtToWlinkOP.WRITE.asUInt.asBool, apb_pslverr, apb_prdata)
            a2l_valid           := true.B
            nstate              := APBIniToWlinkState.IDLE
          }.otherwise{
            apb_pslverr_reg_in  := apb_pslverr
            nstate              := APBIniToWlinkState.APB_HOLD
          }
        }
      
      }.elsewhen(state === APBIniToWlinkState.APB_READ){
        //------------------------------------
        apb_psel                := true.B
        apb_paddr               := l2a_data(71,40)
        apb_pwdata              := l2a_data(39,8)
        apb_pwrite              := false.B
        apb_penable             := true.B
        was_write_in            := false.B
        
        when(apb_pready){
          l2a_accept            := true.B
          when(a2l_ready){
            a2l_data            := Cat(APBTgtToWlinkOP.READ.asUInt.asBool, apb_pslverr, apb_prdata)
            a2l_valid           := true.B
            nstate              := APBIniToWlinkState.IDLE
          }.otherwise{
            apb_pslverr_reg_in  := apb_pslverr
            apb_prdata_reg_in   := apb_prdata
            nstate              := APBIniToWlinkState.APB_HOLD
          }
        }
        
      }.elsewhen(state === APBIniToWlinkState.APB_HOLD){
        //------------------------------------
        when(a2l_ready){
          when(was_write){
            a2l_data              := Cat(APBTgtToWlinkOP.WRITE.asUInt.asBool, apb_pslverr_reg, apb_prdata_reg)
            a2l_valid             := true.B
          }.otherwise{
            a2l_data              := Cat(APBTgtToWlinkOP.READ.asUInt.asBool, apb_pslverr_reg, apb_prdata_reg)
            a2l_valid             := true.B
          }
          nstate                  := APBIniToWlinkState.IDLE
        }
      }.otherwise{
        //------------------------------------
        nstate        := APBIniToWlinkState.IDLE
      }
      
      when(~enable_ff2){
        nstate        := APBIniToWlinkState.IDLE
      }
      
      apb_ini.psel    := apb_psel
      apb_ini.penable := apb_penable
      apb_ini.pwrite  := apb_pwrite
      apb_ini.paddr   := apb_paddr
      apb_ini.pwdata  := apb_pwdata
      apb_ini.pstrb   := apb_pstrb
      apb_ini.pprot   := apb_pprot
      
      apb_pready      := apb_ini.pready
      apb_pslverr     := apb_ini.pslverr
      apb_prdata      := apb_ini.prdata
      
      apbIniFC.module.io.app.a2l_data   := a2l_data
      apbIniFC.module.io.app.a2l_valid  := a2l_valid
      
    }
    
  }
}
