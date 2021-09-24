package wav.wlink

import wav.common._

//import Chisel._
import chisel3._
import chisel3.util._
import chisel3.experimental._
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






class WlinkTxRouter()(implicit p: Parameters) extends LazyModule{
  var largestWidth = 8
  
  val node = WlinkTxNexusNode(
    sourceFn = {
      src => src.foreach { s =>
        if(s.width > largestWidth){largestWidth = s.width}
      }
      val newSrc = WlinkLLSourceTxPortParameters(sources = Seq(WlinkLLSourceTxParameters(Seq(0x0), "TxRouter")), width = largestWidth)
      newSrc
    },
    sinkFn = { seq =>
      seq(0).copy()
    }
  )
  
  lazy val module = new LazyModuleImp(this) with RequireAsyncReset{
    val io = IO(new Bundle{
      val enable  = Input (Bool())
    })
    
    val tx_out              = node.out.head._1
    val (tx_ins, edgesIn)   = node.in.unzip
    val numChannels         = node.in.size
    
    
    val en_ff2              = WavDemetReset(io.enable)
    
    val curr_ch_reg_in      = Wire(UInt(log2Up(numChannels).W))
    val curr_ch_reg         = RegNext(curr_ch_reg_in, 0.U)
    val curr_ch             = Wire(UInt(log2Up(numChannels).W))
    
    val higher_index_pri    = Wire(UInt(log2Up(numChannels).W))
    val higher_index_pri_nxt= Wire(UInt(log2Up(numChannels).W))
    val lower_index_pri     = Wire(UInt(log2Up(numChannels).W))
    
    val higher_index_sel    = Wire(Vec(numChannels, Bool()))
    val lower_index_sel     = Wire(Vec(numChannels, Bool()))
    
    
    for(i <- 0 until numChannels){
      higher_index_sel(i)   := (i.asUInt >  curr_ch_reg) && tx_ins(i).sop
      lower_index_sel(i)    := (i.asUInt <= curr_ch_reg) && tx_ins(i).sop
      tx_ins(i).advance     := tx_out.advance && (curr_ch === i.asUInt)
    }
    
    higher_index_pri        := 0.U
    for(i <- (numChannels-1) to 0 by -1){
      when((i.asUInt > curr_ch_reg) && tx_ins(i).sop){
        higher_index_pri    := i.asUInt
      }
    }
    
    higher_index_pri_nxt    := 0.U
    for(i <- (numChannels-1) to 0 by -1){
      when((i.asUInt > curr_ch) && tx_ins(i).sop){
        higher_index_pri_nxt:= i.asUInt
      }
    }
    
    lower_index_pri         := 0.U
    for(i <- (numChannels-1) to 0 by -1){
      when((i.asUInt < curr_ch_reg) && tx_ins(i).sop){
        lower_index_pri     := i.asUInt
      }
    }
    
    curr_ch_reg_in          := curr_ch_reg
    curr_ch                 := curr_ch_reg
    
    when((curr_ch_reg =/= higher_index_pri) && ~(lower_index_sel.orR)){
      curr_ch_reg_in        := higher_index_pri
      curr_ch               := higher_index_pri
      when(tx_out.advance){
        curr_ch_reg_in      := higher_index_pri_nxt
      }
    }.otherwise{
      when(tx_out.advance){
        curr_ch_reg_in      := Mux(higher_index_sel.orR, higher_index_pri, Mux(lower_index_sel.orR, lower_index_pri, 0.U))
      }
    }
    
    when(~en_ff2){
      curr_ch_reg_in        := 0.U
      curr_ch               := 0.U
    }
    
    for(i <- 0 until numChannels){
      when(i.asUInt === curr_ch){
        tx_out.sop          := tx_ins(i).sop
        tx_out.data_id      := tx_ins(i).data_id
        tx_out.word_count   := tx_ins(i).word_count
        tx_out.data         := tx_ins(i).data
        tx_out.crc          := tx_ins(i).crc
      }
    }
    
  }
  
}


//For some reason I couldn't put this in the class?
object WlinkTxPstateState extends ChiselEnum{
  val APP       = Value(0.U)
  val PREQ      = Value(1.U)
  val PSTATE    = Value(2.U)
} 
    

class WlinkTxPstateCtrl()(implicit p: Parameters) extends LazyModule{

  val node = WlinkTxAdapterNode(
    sourceFn = {src  => WlinkLLSourceTxPortParameters(sources = Seq(WlinkLLSourceTxParameters(Seq(0x0), "TxPstateCtrl")), width = src.width)  },
    sinkFn   = {sink => WlinkLLSinkTxPortParameters  (sinks   = Seq(WlinkLLSinkTxParameters(Seq(0x0),   "TxPstateCtrl")), width = sink.width) }
  )
  
  
  lazy val module = new LazyModuleImp(this) with RequireAsyncReset{
    val io = IO(new Bundle{
      //val enable            = Input (Bool())
      val swi_delay_cycles      = Input (UInt(16.W))    //A setting of 0 will disable the feature
      val swi_num_preq_send     = Input (UInt(3.W))
      val swi_preq_data_id      = Input (UInt(8.W))
      val swi_cycles_post_preq  = Input (UInt(8.W))
      val tx_ready              = Input (Bool())
      val tx_en                 = Output(Bool())
    })
    
    
    
    val nstate        = WireInit(WlinkTxPstateState.APP)
    val state         = RegNext(nstate, WlinkTxPstateState.APP)
    val count_in      = Wire(UInt(16.W))
    val count         = RegNext(count_in, "hffff".U)
    val req_pstate_in = Wire(Bool())
    val req_pstate    = RegNext(req_pstate_in, false.B)
    val pstate_ctrl_in= Wire(Bool())
    val pstate_ctrl   = RegNext(pstate_ctrl_in, false.B)
    
    val sop_in        = Wire(Bool())
    val sop           = RegNext(sop_in, false.B)
        
    val ll_app        = node.in.head._1
    val ll_phy        = node.out.head._1
    
    
    
    nstate            := state
    count_in          := count
    req_pstate_in     := req_pstate
    sop_in            := false.B
    pstate_ctrl_in    := pstate_ctrl
    
    when(state === WlinkTxPstateState.APP){
      req_pstate_in       := false.B
      pstate_ctrl_in      := false.B
      
      when(ll_app.sop){
        count_in          := io.swi_delay_cycles
      }.otherwise{
        when(count === 0.U && io.swi_delay_cycles.orR){
          //req_pstate_in   := true.B
          pstate_ctrl_in  := true.B
          sop_in          := true.B
          nstate          := WlinkTxPstateState.PREQ
        }.otherwise{
          count_in        := count - 1.U
        }
      }
      
    }.elsewhen(state === WlinkTxPstateState.PREQ){
    
      sop_in              := true.B
      when(ll_phy.advance){
        when(count === io.swi_num_preq_send){
          count_in        := io.swi_cycles_post_preq
          sop_in          := false.B
          //req_pstate_in   := true.B
          nstate          := WlinkTxPstateState.PSTATE
        }.otherwise{
          count_in        := count + 1.U
        }
      }
      
    }.elsewhen(state === WlinkTxPstateState.PSTATE){
      
      when(count === 0.U){
        req_pstate_in     := true.B
      }.otherwise{
        count_in          := count - 1.U
      }
      
      when(~io.tx_ready){
        when(ll_app.sop){
          req_pstate_in   := false.B
          pstate_ctrl_in  := false.B
          count_in        := io.swi_delay_cycles
          nstate          := WlinkTxPstateState.APP
        }
      }
      
    }.otherwise{
      req_pstate_in       := false.B
      nstate              := WlinkTxPstateState.APP
    }
    
    //when(req_pstate){
    when(pstate_ctrl){
      ll_phy.sop          := sop
      ll_phy.data_id      := io.swi_preq_data_id
      ll_phy.word_count   := 0.U                    //Use later????
      ll_phy.data         := 0.U
      ll_phy.crc          := 0.U
      
      ll_app.advance      := false.B
    }.otherwise{
      ll_phy.sop          := ll_app.sop       
      ll_phy.data_id      := ll_app.data_id   
      ll_phy.word_count   := ll_app.word_count
      ll_phy.data         := ll_app.data   
      ll_phy.crc          := ll_app.crc   
      ll_app.advance      := ll_phy.advance
    }
    
    io.tx_en  := ~req_pstate
    
  }
  
}

class WlinkTxPipeLineStage()(implicit p: Parameters) extends LazyModule{
  val node = WlinkTxNexusNode(
    sourceFn = { src =>
      src(0).copy()
    },
    sinkFn = { seq =>
      seq(0).copy()
    }
  )
  
  lazy val module = new LazyModuleImp(this) with RequireAsyncReset{
    
    val tx_out    = node.out.head._1
    val tx_in     = node.in.head._1
    
    val empty_in  = Wire(Bool())
    val empty     = RegNext(empty_in, true.B)

    val ll        = RegInit(0.U.asTypeOf(Output(chiselTypeOf(tx_out))))
    
    empty_in      := Mux(tx_in.sop, false.B, Mux(tx_out.advance, true.B, empty))
    
    tx_in.advance       := (empty & tx_in.sop & ~tx_out.sop) | (tx_out.advance & tx_in.sop)
    val update          =  (empty & tx_in.sop & ~tx_out.sop) |  tx_out.advance
    
    ll.sop              := Mux(update, tx_in.sop,        ll.sop)
    ll.data_id          := Mux(update, tx_in.data_id,    ll.data_id)
    ll.word_count       := Mux(update, tx_in.word_count, ll.word_count)
    ll.data             := Mux(update, tx_in.data,       ll.data)
    ll.crc              := Mux(update, tx_in.crc,        ll.crc)
    
    
    tx_out.sop          := ll.sop       
    tx_out.data_id      := ll.data_id   
    tx_out.word_count   := ll.word_count
    tx_out.data         := ll.data   
    tx_out.crc          := ll.crc   
    
    
    
  }
}

object WlinkTxPipeLineStage{
  def apply()(implicit p: Parameters): WlinkTxNexusNode = {
    val wlTxPl = LazyModule(new WlinkTxPipeLineStage())
    wlTxPl.node
  }
}


class WlinkRxRouter()(implicit p: Parameters) extends LazyModule{
  
  var largestWidth = 8
  val node = WlinkRxNexusNode(
    sourceFn = { seq => 
      seq(0).copy()
    },
    sinkFn = { 
      sink => sink.foreach { s =>
        if(s.width > largestWidth){largestWidth = s.width}
      }
      val newSink = WlinkLLSinkRxPortParameters(sinks = Seq(WlinkLLSinkRxParameters(Seq(0x0), "RxRouter")), width = largestWidth)
      newSink
    },
  )
  
  lazy val module = new LazyModuleImp(this) with RequireAsyncReset{
    
    val rx_in               = node.in.head._1
    val (rx_outs, edgesOut) = node.out.unzip
    val numChannels         = node.out.size
    
    for(i <- 0 until numChannels){
      rx_outs(i).sop          := rx_in.sop
      rx_outs(i).data_id      := rx_in.data_id    
      rx_outs(i).word_count   := rx_in.word_count 
      rx_outs(i).data         := rx_in.data       
      rx_outs(i).valid        := rx_in.valid      
      rx_outs(i).crc          := rx_in.crc
    }
  }
}


// Just grabs the 
class WlinkRxPstateCtrl()(implicit p: Parameters) extends LazyModule{
  
  //We actually don't need the data in this case, so just default to something small
  val node = new WlinkRxSinkNode(Seq(WlinkLLSinkRxPortParameters(sinks = Seq(WlinkLLSinkRxParameters(Seq(0x0), "RxPstateCtrl")), width = 8)))
  
  lazy val module = new LazyModuleImp(this) with RequireAsyncReset{
    val io = IO(new Bundle{
      val enter_lp          = Output(Bool())
      val swi_preq_data_id  = Input (UInt(8.W))
    })
    
    val ll              = node.in.head._1
    
    io.enter_lp         := ll.sop & ll.valid & (ll.data_id === io.swi_preq_data_id) //crc not valid on short packet
  }
  
}




//=====================================================
//
//=====================================================


object WlinkTxLLState extends ChiselEnum{
  val IDLE      = Value(0.U)
  val WC0       = Value(1.U)
  val WC1       = Value(2.U)
  val ECC       = Value(3.U)
  val LONG      = Value(4.U)
  val CRC0      = Value(5.U)
  val CRC1      = Value(6.U)
} 

class WlinkTxLinkLayer(
  val numLanes      : Int = 8, 
  val phyDataWidth  : Int = 16, 
  val width         : Int = 128,
  val validLaneSeq  : Seq[Boolean] = Seq(true, true, false, true, false, false, false, true) //this is 0 -> larger!
)(implicit p: Parameters) extends LazyModule{
  
  val node = new WlinkTxSinkNode(Seq(WlinkLLSinkTxPortParameters(sinks = Seq(WlinkLLSinkTxParameters(Seq(0x0), "WlinkTxLL")), width = width)))
  
  lazy val module = new LazyModuleImp(this) with RequireAsyncReset{
    val io = IO(new Bundle{
      val enable                = Input (Bool())
      val swi_short_packet_max  = Input (UInt(8.W))
      val active_lanes          = Input (UInt(8.W))
      
      val swi_err_inj           = Input (Bool())
      val swi_err_inj_data_id   = Input (UInt(8.W))
      val swi_err_inj_byte      = Input (UInt(8.W))
      val swi_err_inj_bit       = Input (UInt(3.W))
      
      
      val ll_tx_valid           = Input (Bool())
      val link_data             = Output(UInt((numLanes*phyDataWidth).W))
      val link_idle             = Output(Bool())
      val ll_tx_state           = Output(UInt(4.W))
    })
    
    val ll = node.in.head._1
    val (blah, edgesIn) = node.in.unzip
    val inputWidth = edgesIn(0).source.width
    
    //val numBytes      = (width/8.0).ceil.toInt + 6
    val numBytes      = (inputWidth/8.0).ceil.toInt + 6
    val ll_byte_index = Wire(Vec(numBytes, UInt(8.W)))
    
    val is_short_pkt              = ll.data_id <= io.swi_short_packet_max
    
    val ecc_check                 = Module(new WlinkEccSyndrome)
    ecc_check.ph_in               := Cat(ll.word_count, ll.data_id)
    ecc_check.rx_ecc              := 0.U
    
    
    // Error Injection
    // Ability to "inject" a bit error somewhere in the packet. We can select a
    // particular packet type to perform this on, and the exact bit to invert
    val err_inj_ff2       = WavDemetReset(io.swi_err_inj)
    val err_inj_ff3       = RegNext(err_inj_ff2, false.B)
    val err_inj_re        = err_inj_ff2 & ~err_inj_ff3
    val err_inj_smack_in  = Wire(Bool())
    val err_inj_smack     = RegNext(err_inj_smack_in, false.B)
    err_inj_smack_in      := Mux(err_inj_re, true.B, Mux(ll.advance & ll.sop & (ll.data_id === io.swi_err_inj_data_id), false.B, err_inj_smack))
    val err_inj           = err_inj_smack & ll.sop & (ll.data_id === io.swi_err_inj_data_id)
    
    // Indexing each byte (basically a "flattened" byte index of the application data
    // Due to the wordcount check, it's possible for this to override something earlier
    // when a short packet uses the WC for data. So assign these first and have
    // the index override later
    ll_byte_index(numBytes-2)        := 0.U
    ll_byte_index(numBytes-1)        := 0.U
    
    ll_byte_index(0)  := Mux(err_inj && (io.swi_err_inj_byte === 0.U), (ll.data_id          ^ (1.U << io.swi_err_inj_bit)), ll.data_id         )
    ll_byte_index(1)  := Mux(err_inj && (io.swi_err_inj_byte === 1.U), (ll.word_count(7,0)  ^ (1.U << io.swi_err_inj_bit)), ll.word_count(7,0) )//ll.word_count(7,0)
    ll_byte_index(2)  := Mux(err_inj && (io.swi_err_inj_byte === 2.U), (ll.word_count(15,8) ^ (1.U << io.swi_err_inj_bit)), ll.word_count(15,8))//ll.word_count(15,8)
    ll_byte_index(3)  := Mux(err_inj && (io.swi_err_inj_byte === 3.U), (ecc_check.calc_ecc  ^ (1.U << io.swi_err_inj_bit)), ecc_check.calc_ecc )//ecc_check.calc_ecc
    for(i <- 0 until (numBytes - 6)){
      //ll_byte_index(i+4) := ll.data((8*i)+7, 8*i)
      ll_byte_index(i+4) := Mux(err_inj && (io.swi_err_inj_byte === (i+4).asUInt), (ll.data((8*i)+7, 8*i) ^ (1.U << io.swi_err_inj_bit)), ll.data((8*i)+7, 8*i))
    }
    
    when(~is_short_pkt){
      ll_byte_index(ll.word_count+4.U) := Mux(err_inj && (io.swi_err_inj_byte === ll.word_count+4.U), (ll.crc(7,0)  ^ (1.U << io.swi_err_inj_bit)), ll.crc(7,0))//ll.crc(7,0)
      ll_byte_index(ll.word_count+5.U) := Mux(err_inj && (io.swi_err_inj_byte === ll.word_count+4.U), (ll.crc(15,8) ^ (1.U << io.swi_err_inj_bit)), ll.crc(15,8))//ll.crc(15,8)
    }
    
    when((ll.word_count =/= numBytes.asUInt-6.U) && ~is_short_pkt){
      ll_byte_index(numBytes-1)        := 0.U
    }
    when((ll.word_count < numBytes.asUInt-7.U) && ~is_short_pkt){
      ll_byte_index(numBytes-2)        := 0.U
    }
    
    
    
    
    
    val nstate                    = WireInit(WlinkTxLLState.IDLE)
    val state                     = RegNext(nstate, WlinkTxLLState.IDLE)
    val enable_ff2                = WavDemetReset(io.enable)
    
    
    val link_data_reg_in          = Wire(UInt((numLanes*phyDataWidth).W))
    val link_data_reg             = RegNext(link_data_reg_in, 0.U)
    val link_data_byte_index      = Wire(Vec((numLanes*phyDataWidth/8), UInt(8.W)))   //Byte Index for each cycle (flat)
    val link_data_lane_index      = Wire(Vec(numLanes,   UInt(phyDataWidth.W)))       //Byte Index adjusted for each lane (stipped)
    val active_lanes              = io.active_lanes
    
    val byte_count_in             = Wire(UInt(17.W))
    val byte_count                = RegNext(byte_count_in, 0.U)
    
    val bytesPerCycle     = ((active_lanes + 1.U) << 1)                               //UInt value of number of bytes per cycle
    def incrByteCount     : UInt = {byte_count + bytesPerCycle}
    val setBytecount      = bytesPerCycle
    val topIndex          = Mux(is_short_pkt, 4.U, ll.word_count + 6.U)               //TODO: Add CRC option here
    val endOfPacket       = incrByteCount >= topIndex
    
    //This updates the link_data interms of the bytes that are being sent out this cycle
    //Here we are mainly just selecting N bytes of data, where N is the number of bytes
    //possible to send (e.g. 4 if 2lane@16bit). The swizzle to the proper byte stripe
    //is handled later
    //
    //Since supporting every possible lane combination can induce deep combinational
    //paths, we have the option to limit the number of valid lanes by setting
    //the validLaneSeq to give us the possible combinations.
    //e.g. you may have an 8lane, but only want to support 1/2/4/8 lane configs
    // or you have something like 10 lanes and want to support 1/5/10
    
    def updateLinkData: Unit = {
      for(i <- 0 until numLanes){
        val baseByte = i*2
        link_data_byte_index(baseByte)   := Mux((byte_count + baseByte.asUInt)       < numBytes.asUInt , ll_byte_index(byte_count + baseByte.asUInt),       0.U)
        link_data_byte_index(baseByte+1) := Mux((byte_count + baseByte.asUInt + 1.U) < numBytes.asUInt , ll_byte_index(byte_count + baseByte.asUInt + 1.U), 0.U)
      }
    }
    
    //Initialize vectors
    for(i <- 0 until numLanes*phyDataWidth/8){ link_data_byte_index(i) := 0.U }
    for(i <- 0 until numLanes)               { link_data_lane_index(i) := 0.U }
    
    
    nstate                  := state
    link_data_reg_in        := link_data_reg
    ll.advance              := false.B
    
    byte_count_in           := byte_count
    
    
    when(state === WlinkTxLLState.IDLE){
      when(enable_ff2 && io.ll_tx_valid){
        when(ll.sop){
          byte_count_in         := setBytecount
          updateLinkData
          ll.advance            := endOfPacket
          byte_count_in         := Mux(endOfPacket, 0.U, incrByteCount)
          nstate                := Mux(endOfPacket, WlinkTxLLState.IDLE, WlinkTxLLState.LONG)
        }
      }
    }.elsewhen(state === WlinkTxLLState.LONG){
      when(io.ll_tx_valid){
        updateLinkData
        ll.advance                := endOfPacket
        byte_count_in             := Mux(endOfPacket, 0.U, incrByteCount)
        nstate                    := Mux(endOfPacket, WlinkTxLLState.IDLE, WlinkTxLLState.LONG)
      }
    }.otherwise{
      nstate                := WlinkTxLLState.IDLE
    }
    
    
    
    //Arranging the byte index to the specific lane
    //Handles the alignment for 32/16/8bit
    for(i <- 0 until numLanes){
      phyDataWidth match{
        case 16 => {
          for(laneOffset <- 0 until numLanes){
            when(laneOffset.asUInt === active_lanes){
            //when((laneOffset.asUInt === active_lanes) && validLaneSeq(laneOffset).asBool){
              link_data_lane_index(i) := Cat(link_data_byte_index(i + laneOffset + 1), link_data_byte_index(i))
            }
          }
        }
      }
    }
    
    link_data_reg_in    := link_data_lane_index.asUInt
    
    
    io.link_data        := link_data_reg
    io.link_idle        := (state === WlinkTxLLState.IDLE) && ~ll.sop
    io.ll_tx_state      := state.asUInt
    
  }
}




object WlinkRxLLState extends ChiselEnum{
  val IDLE      = Value(0.U)
  val LONG      = Value(1.U)
  val ERROR     = Value(2.U)
} 

class WlinkRxLinkLayer(
  val numLanes      : Int = 8, 
  val phyDataWidth  : Int = 16, 
  val width         : Int = 8,
  val validLaneSeq  : Seq[Boolean] = Seq(true, true, false, true, false, false, false, true) //this is 0 -> larger!
)(implicit p: Parameters) extends LazyModule{
  
  val node = new WlinkRxSourceNode(
    Seq(WlinkLLSourceRxPortParameters(sources = Seq(WlinkLLSourceRxParameters(Seq(0x0), "WlinkRxLL")), width = width))
  )

  
  lazy val module = new LazyModuleImp(this) with RequireAsyncReset{
    val io = IO(new Bundle{
      val enable                = Input (Bool())
      val swi_short_packet_max  = Input (UInt(8.W))
      val active_lanes          = Input (UInt(8.W))
      
      val swi_ecc_corrupt_errs  = Input (Bool())
      val ecc_corrected         = Output(Bool())
      val ecc_corrupted         = Output(Bool())
      val ll_rx_valid           = Input (Bool())
      val link_data             = Input (UInt((numLanes*phyDataWidth).W))
      val ll_rx_state           = Output(UInt(4.W))
    })
    
    
    val nstate                    = WireInit(WlinkRxLLState.IDLE)
    val state                     = RegNext(nstate, WlinkRxLLState.IDLE)
    val enable_ff2                = WavDemetReset(io.enable)
    
    val ll = node.out.head._1
    val (blah, edgesOut) = node.out.unzip
    val outputWidth = edgesOut(0).sink.width
    
    //val numBytes      = (width/8.0).ceil.toInt + 6
    val numBytes      = (outputWidth/8.0).ceil.toInt + 6
    val ll_byte_index = RegInit(VecInit(Seq.fill(numBytes)(0.U(8.W)))) //RegInit(Vec(numBytes, UInt(8.W)))
    
    
    val active_lanes              = io.active_lanes
    val link_data_lane_index      = Wire(Vec(numLanes,   UInt(phyDataWidth.W)))       //Byte Index adjusted for each lane (stipped)
    val link_data_byte_index      = Wire(Vec((numLanes*phyDataWidth/8), UInt(8.W)))   //Byte Index for each cycle (flat)
    for(i <- 0 until numLanes*phyDataWidth/8){ link_data_byte_index(i) := 0.U }
    
    val is_short_pkt      = Wire(Bool())
        
    val byte0_reg_in      = Wire(UInt(8.W))
    val byte0_reg         = RegNext(byte0_reg_in, 0.U)
    val byte1_reg_in      = Wire(UInt(8.W))
    val byte1_reg         = RegNext(byte1_reg_in, 0.U)
    val valid_byte_reg    = byte0_reg.orR | byte1_reg.orR     //I don't like this. Need to find something better
    
    
    val ecc_check         = Module(new WlinkEccSyndrome)
    
    val corrected_ph      = Wire(UInt(24.W))
    corrected_ph          := ecc_check.corrected_ph
    is_short_pkt          := (corrected_ph(7,0) <= io.swi_short_packet_max) && (corrected_ph(7,0) =/= 0.U) && ~ecc_check.corrupted
    val is_long_pkt       = (corrected_ph(7,0) >  io.swi_short_packet_max) && ~ecc_check.corrupted
    val is_short_pkt_prev_in = Wire(Bool())
    val is_short_pkt_prev = RegNext(is_short_pkt_prev_in, false.B)
    is_short_pkt_prev_in  := Mux(is_short_pkt_prev, false.B, is_short_pkt)
    
    val valid_in          = Wire(Bool())
    val valid             = RegNext(valid_in, false.B)
    val word_count_in     = Wire(UInt(16.W))
    val word_count        = RegNext(word_count_in, false.B)
    val byte_index_crc    = Wire(UInt(17.W))
    
    
    val byte_count_in     = Wire(UInt(17.W))
    val byte_count        = RegNext(byte_count_in, 0.U)
    val bytesPerCycle     = ((active_lanes + 1.U) << 1)                               //UInt value of number of bytes per cycle
    def incrByteCount     : UInt = {byte_count + bytesPerCycle}
    val setBytecount      = bytesPerCycle
    val topIndex          = word_count_in + 6.U               //TODO: Add CRC option here || How do we handle single cycle?
    val endOfPacket       = incrByteCount >= topIndex
    
    
    
    val upperBC = log2Ceil(numBytes).toInt
    //WORKS
    //This causes some deeper combinational paths. Need to find a good way to
    //have the logic created where it doesn't account for EVERY combination
//     def updateAppData: Unit = {
//       for(i <- 0 until numLanes ){
//         val byte_base     = (i*2).asUInt
//         val byte_base_p1  = ((i*2)+1).asUInt
//         ll_byte_index(byte_count + byte_base)    := link_data_byte_index(byte_base)
//         ll_byte_index(byte_count + byte_base_p1) := link_data_byte_index(byte_base_p1)
//       }
//     }
    def updateAppDataIdleState: Unit = {
      //for(i <- 4 until (numLanes*phyDataWidth/8) ){
        for(i <- (numLanes*phyDataWidth/8)-1 to 4 by -1){
        ll_byte_index(i)    := link_data_byte_index(i)
      }
    }  
    def updateAppData: Unit = {
      //for(i <- 0 until (numLanes*phyDataWidth/8) ){
      for(i <- (numLanes*phyDataWidth/8)-1 to 0 by -1){
        ll_byte_index(byte_count + i.asUInt)    := link_data_byte_index(i)
      }
    }    
    
    
    
        
    byte0_reg_in            := Mux(state === WlinkRxLLState.IDLE & io.ll_rx_valid, link_data_byte_index(0), 0.U)
    byte1_reg_in            := Mux(state === WlinkRxLLState.IDLE & io.ll_rx_valid, link_data_byte_index(1), 0.U)
    
    nstate                  := state
    valid_in                := false.B
    word_count_in           := word_count
    byte_count_in           := byte_count    
    byte_index_crc          := word_count
    
    ecc_check.ph_in         := 0.U
    ecc_check.rx_ecc        := 0.U
    
    when(state === WlinkRxLLState.IDLE){
      byte_count_in               := 0.U
      when(enable_ff2 && io.ll_rx_valid){
        when(active_lanes === 0.U){
          when(valid_byte_reg){
            ecc_check.ph_in         := Cat(link_data_byte_index(0), byte1_reg, byte0_reg)
            ecc_check.rx_ecc        := link_data_byte_index(1)
            when(is_short_pkt && ~is_short_pkt_prev){
              valid_in              := true.B
              ll_byte_index(0)      := ecc_check.corrected_ph(7,0)
              ll_byte_index(1)      := ecc_check.corrected_ph(15,8)
              ll_byte_index(2)      := ecc_check.corrected_ph(23,16)
            }
            when(is_long_pkt && ~is_short_pkt_prev){
              byte_count_in         := 4.U
              ll_byte_index(0)      := ecc_check.corrected_ph(7,0)
              ll_byte_index(1)      := ecc_check.corrected_ph(15,8)
              ll_byte_index(2)      := ecc_check.corrected_ph(23,16)
              word_count_in         := ecc_check.corrected_ph(23,8)
              nstate                := WlinkRxLLState.LONG
            }
          }
        }.otherwise{
          if(numLanes > 1){ //to protect against out of bounds exception during elab
            ecc_check.ph_in         := Cat(link_data_byte_index(2), link_data_byte_index(1), link_data_byte_index(0))
            ecc_check.rx_ecc        := link_data_byte_index(3)
            when(is_short_pkt){
              valid_in              := true.B
              ll_byte_index(0)      := ecc_check.corrected_ph(7,0)
              ll_byte_index(1)      := ecc_check.corrected_ph(15,8)
              ll_byte_index(2)      := ecc_check.corrected_ph(23,16)
            }
            when(is_long_pkt){
              //updateAppData
              updateAppDataIdleState
              ll_byte_index(0)      := ecc_check.corrected_ph(7,0)
              ll_byte_index(1)      := ecc_check.corrected_ph(15,8)
              ll_byte_index(2)      := ecc_check.corrected_ph(23,16)
              word_count_in         := ecc_check.corrected_ph(23,8)
              byte_count_in         := Mux(endOfPacket, 0.U, incrByteCount)
              valid_in              := endOfPacket
              nstate                := Mux(endOfPacket, WlinkRxLLState.IDLE, WlinkRxLLState.LONG)//WlinkRxLLState.LONG
            }
            when(ecc_check.corrupted & io.swi_ecc_corrupt_errs){
              valid_in              := false.B
              nstate                := WlinkRxLLState.ERROR
            }
          }
        }
        
        
      }
    }.elsewhen(state === WlinkRxLLState.LONG){
      when(io.ll_rx_valid){
        //updateAppData
        updateAppData
        byte_count_in         := Mux(endOfPacket, 0.U, incrByteCount)
        valid_in              := endOfPacket
        nstate                := Mux(endOfPacket, WlinkRxLLState.IDLE, WlinkRxLLState.LONG)
      }
    }.elsewhen(state === WlinkRxLLState.ERROR){
      nstate                := WlinkRxLLState.ERROR
    }.otherwise{
      nstate                := WlinkRxLLState.IDLE
    }
    
    
    
    //Arranging the byte index to the specific lane
    //Handles the alignment for 32/16/8bit
//     for(i <- 0 until numLanes){
//       phyDataWidth match{
//         case 16 => {
//           when(i.asUInt <= active_lanes){
//             link_data_byte_index(i.asUInt)                       := link_data_lane_index(i)(7 ,0)
//             link_data_byte_index(i.asUInt + active_lanes + 1.U)  := link_data_lane_index(i)(15,8)
//           }
//         }
//       }
//     }
    
    for(i <- 0 until numLanes){
      phyDataWidth match{
        case 16 => {
          //when(i.asUInt === active_lanes){
          when((i.asUInt === active_lanes) && validLaneSeq(i).asBool){
            for(laneOffset <- 0 to i){
              link_data_byte_index(laneOffset)          := link_data_lane_index(laneOffset)(7 ,0)
              link_data_byte_index(laneOffset+i+1)      := link_data_lane_index(laneOffset)(15,8)
            }
          }
        }
      }
    }
    
    for(i <- 0 until numLanes){
      link_data_lane_index(i) := io.link_data((phyDataWidth*i)+phyDataWidth-1, phyDataWidth*i)
    }
    
    ll.valid      := valid
    ll.sop        := valid    //same for now
    ll.data_id    := ll_byte_index(0)//data_id
    ll.word_count := Cat(ll_byte_index(2), ll_byte_index(1))//word_count
    
    val app_data_flat = Wire(Vec((outputWidth/8.0).ceil.toInt, UInt(8.W)))
    for(i <- 0 until (outputWidth/8.0).ceil.toInt){
      app_data_flat(i) := ll_byte_index(i+4)
    }
    ll.data       := app_data_flat.asUInt
    
    ll.crc        := Cat(ll_byte_index(byte_index_crc + 5.U), ll_byte_index(byte_index_crc + 4.U))
    
    io.ecc_corrected  := ecc_check.corrected
    io.ecc_corrupted  := ecc_check.corrupted
    
    io.ll_rx_state    := state.asUInt
  }
}


class WlinkEccSyndrome extends MultiIOModule{
  val ph_in         = IO(Input (UInt(24.W)))
  val rx_ecc        = IO(Input (UInt(8.W)))
  
  val calc_ecc      = IO(Output(UInt(8.W)))
  val corrected_ph  = IO(Output(UInt(24.W)))
  val corrected     = IO(Output(Bool()))
  val corrupted     = IO(Output(Bool()))
  
  val ecc           = Wire(Vec(8, Bool()))   //generated value
  val syndrome      = Wire(UInt(8.W))
  
  
  ecc(0) := (ph_in(0) ^ 
             ph_in(1) ^ 
             ph_in(2) ^ 
             ph_in(4) ^ 
             ph_in(5) ^ 
             ph_in(7) ^ 
             ph_in(10) ^ 
             ph_in(11) ^ 
             ph_in(13) ^ 
             ph_in(16) ^ 
             ph_in(20) ^ 
             ph_in(21) ^ 
             ph_in(22) ^ 
             ph_in(23))
  ecc(1) := (ph_in(0) ^ 
             ph_in(1) ^ 
             ph_in(3) ^ 
             ph_in(4) ^ 
             ph_in(6) ^ 
             ph_in(8) ^ 
             ph_in(10) ^ 
             ph_in(12) ^ 
             ph_in(14) ^ 
             ph_in(17) ^ 
             ph_in(20) ^ 
             ph_in(21) ^ 
             ph_in(22) ^ 
             ph_in(23))
  ecc(2) := (ph_in(0) ^ 
             ph_in(2) ^ 
             ph_in(3) ^ 
             ph_in(5) ^ 
             ph_in(6) ^ 
             ph_in(9) ^ 
             ph_in(11) ^ 
             ph_in(12) ^ 
             ph_in(15) ^ 
             ph_in(18) ^ 
             ph_in(20) ^ 
             ph_in(21) ^ 
             ph_in(22))
  ecc(3) := (ph_in(1) ^ 
             ph_in(2) ^ 
             ph_in(3) ^ 
             ph_in(7) ^ 
             ph_in(8) ^ 
             ph_in(9) ^ 
             ph_in(13) ^ 
             ph_in(14) ^ 
             ph_in(15) ^ 
             ph_in(19) ^ 
             ph_in(20) ^ 
             ph_in(21) ^ 
             ph_in(23))
  
  ecc(4) := (ph_in(4) ^ 
             ph_in(5) ^ 
             ph_in(6) ^ 
             ph_in(7) ^ 
             ph_in(8) ^ 
             ph_in(9) ^ 
             ph_in(16) ^ 
             ph_in(17) ^ 
             ph_in(18) ^ 
             ph_in(19) ^ 
             ph_in(20) ^ 
             ph_in(22) ^ 
             ph_in(23))
  
  ecc(5) := (ph_in(10) ^ 
             ph_in(11) ^ 
             ph_in(12) ^ 
             ph_in(13) ^ 
             ph_in(14) ^ 
             ph_in(15) ^ 
             ph_in(16) ^ 
             ph_in(17) ^ 
             ph_in(18) ^ 
             ph_in(19) ^ 
             ph_in(21) ^ 
             ph_in(22) ^ 
             ph_in(23))
  
  ecc(6) := false.B
  ecc(7) := false.B
  
  syndrome      := rx_ecc ^ ecc.asUInt
  calc_ecc      := ecc.asUInt
  
  
  //Default state is corrupted
  corrected     := false.B
  corrupted     := true.B
  
  corrected_ph  := ph_in
  
  
  switch(syndrome){
    //It's gud bruh
    is("h00".U){ corrected_ph := ph_in; corrupted := false.B}
    
    //Single Bit Error
    is("h07".U){ corrected_ph := Cat(ph_in(23,1),  ~ph_in(0)              ); corrected := true.B; corrupted := false.B;}
    is("h0B".U){ corrected_ph := Cat(ph_in(23,2),  ~ph_in(1),  ph_in(0)   ); corrected := true.B; corrupted := false.B;}
    is("h0D".U){ corrected_ph := Cat(ph_in(23,3),  ~ph_in(2),  ph_in(1,0) ); corrected := true.B; corrupted := false.B;}
    is("h0E".U){ corrected_ph := Cat(ph_in(23,4),  ~ph_in(3),  ph_in(2,0) ); corrected := true.B; corrupted := false.B;}
    is("h13".U){ corrected_ph := Cat(ph_in(23,5),  ~ph_in(4),  ph_in(3,0) ); corrected := true.B; corrupted := false.B;}
    is("h15".U){ corrected_ph := Cat(ph_in(23,6),  ~ph_in(5),  ph_in(4,0) ); corrected := true.B; corrupted := false.B;}
    is("h16".U){ corrected_ph := Cat(ph_in(23,7),  ~ph_in(6),  ph_in(5,0) ); corrected := true.B; corrupted := false.B;}
    is("h19".U){ corrected_ph := Cat(ph_in(23,8),  ~ph_in(7),  ph_in(6,0) ); corrected := true.B; corrupted := false.B;}
    is("h1A".U){ corrected_ph := Cat(ph_in(23,9),  ~ph_in(8),  ph_in(7,0) ); corrected := true.B; corrupted := false.B;}
    is("h1C".U){ corrected_ph := Cat(ph_in(23,10), ~ph_in(9),  ph_in(8,0) ); corrected := true.B; corrupted := false.B;}
    is("h23".U){ corrected_ph := Cat(ph_in(23,11), ~ph_in(10), ph_in(9,0) ); corrected := true.B; corrupted := false.B;}
    is("h25".U){ corrected_ph := Cat(ph_in(23,12), ~ph_in(11), ph_in(10,0)); corrected := true.B; corrupted := false.B;}
    is("h26".U){ corrected_ph := Cat(ph_in(23,13), ~ph_in(12), ph_in(11,0)); corrected := true.B; corrupted := false.B;}
    is("h29".U){ corrected_ph := Cat(ph_in(23,14), ~ph_in(13), ph_in(12,0)); corrected := true.B; corrupted := false.B;}
    is("h2A".U){ corrected_ph := Cat(ph_in(23,15), ~ph_in(14), ph_in(13,0)); corrected := true.B; corrupted := false.B;}
    is("h2C".U){ corrected_ph := Cat(ph_in(23,16), ~ph_in(15), ph_in(14,0)); corrected := true.B; corrupted := false.B;}
    is("h31".U){ corrected_ph := Cat(ph_in(23,17), ~ph_in(16), ph_in(15,0)); corrected := true.B; corrupted := false.B;}
    is("h32".U){ corrected_ph := Cat(ph_in(23,18), ~ph_in(17), ph_in(16,0)); corrected := true.B; corrupted := false.B;}
    is("h34".U){ corrected_ph := Cat(ph_in(23,19), ~ph_in(18), ph_in(17,0)); corrected := true.B; corrupted := false.B;}
    is("h38".U){ corrected_ph := Cat(ph_in(23,20), ~ph_in(19), ph_in(18,0)); corrected := true.B; corrupted := false.B;}
    is("h1F".U){ corrected_ph := Cat(ph_in(23,21), ~ph_in(20), ph_in(19,0)); corrected := true.B; corrupted := false.B;}
    is("h2F".U){ corrected_ph := Cat(ph_in(23,22), ~ph_in(21), ph_in(20,0)); corrected := true.B; corrupted := false.B;}
    is("h37".U){ corrected_ph := Cat(ph_in(23),    ~ph_in(22), ph_in(21,0)); corrected := true.B; corrupted := false.B;}
    is("h3B".U){ corrected_ph := Cat(              ~ph_in(23), ph_in(22,0)); corrected := true.B; corrupted := false.B;}
    
    //Ecc has error
    is("h01".U){ corrected_ph := ph_in; corrected := true.B; corrupted := false.B;}
    is("h02".U){ corrected_ph := ph_in; corrected := true.B; corrupted := false.B;}
    is("h04".U){ corrected_ph := ph_in; corrected := true.B; corrupted := false.B;}
    is("h08".U){ corrected_ph := ph_in; corrected := true.B; corrupted := false.B;}
    is("h10".U){ corrected_ph := ph_in; corrected := true.B; corrupted := false.B;}
    is("h20".U){ corrected_ph := ph_in; corrected := true.B; corrupted := false.B;}
    is("h40".U){ corrected_ph := ph_in; corrected := true.B; corrupted := false.B;}
    is("h80".U){ corrected_ph := ph_in; corrected := true.B; corrupted := false.B;}
  }
  
  
  
}

//=====================================================
// Old Slink Version
//=====================================================
class WlinkLLTx(val numLanes: Int, val phyDataWidth: Int, val width: Int)(implicit p: Parameters) extends LazyModule{
  val node = new WlinkTxSinkNode(Seq(WlinkLLSinkTxPortParameters(sinks = Seq(WlinkLLSinkTxParameters(Seq(0x0), "WlinkLLTx")), width = width)))
  
  lazy val module = new LazyModuleImp(this) with RequireAsyncReset{
    val io = IO(new Bundle{
      val clk                   = Input (Bool())
      val reset                 = Input (Bool())
      val enable                = Input (Bool())
      val swi_short_packet_max  = Input (UInt(8.W))
      
      val active_lanes          = Input (UInt(3.W))
      //val sds_sent              = Input (Bool())
      
      val ll_tx_valid           = Input (Bool())
      val link_data             = Output(UInt((numLanes*phyDataWidth).W))
      val link_idle             = Output(Bool())
      val ll_tx_state           = Output(UInt(4.W))
    })
    
    val ll_app = node.in.head._1
    
    val ll_tx = Module(new slink_ll_tx(numLanes, phyDataWidth, width))
    ll_tx.io.clk                  := io.clk
    ll_tx.io.reset                := io.reset
    ll_tx.io.enable               := io.enable
    ll_tx.io.swi_short_packet_max := io.swi_short_packet_max
    ll_tx.io.active_lanes         := io.active_lanes
    ll_tx.io.sds_sent             := true.B//io.sds_sent      //SEE IF THIS WORKS
    
    ll_tx.io.delimeter  := 0.U //TEMP!!!
    
    ll_tx.io.sop        := ll_app.sop
    ll_tx.io.data_id    := ll_app.data_id
    ll_tx.io.word_count := ll_app.word_count
    ll_tx.io.app_data   := ll_app.data
    ll_app.advance      := ll_tx.io.advance
    
    ll_tx.io.crc        := ll_app.crc
    
    ll_tx.io.valid      := false.B //not used
    
    ll_tx.io.ll_tx_valid  := io.ll_tx_valid
    io.link_data          := ll_tx.io.link_data
    io.link_idle          := ll_tx.io.link_idle
    io.ll_tx_state        := ll_tx.io.ll_tx_state
    
  }  
}


class slink_ll_tx(
  numLanes      : Int,
  phyDataWidth  : Int,
  appDataWidth  : Int
  
) extends BlackBox (Map(
  "NUM_LANES"       -> numLanes,
  "DATA_WIDTH"      -> phyDataWidth,
  "APP_DATA_WIDTH"  -> appDataWidth
)) with HasBlackBoxResource{

  val io = IO(new Bundle{
    val clk         = Input (Bool())
    val reset       = Input (Bool())
    val enable      = Input (Bool())
    
    val swi_short_packet_max  = Input (UInt(8.W))
    
    val sop         = Input (Bool())
    val data_id     = Input (UInt(8.W))
    val word_count  = Input (UInt(16.W))
    val app_data    = Input (UInt(appDataWidth.W))
    val advance     = Output(Bool())
    val crc         = Input (UInt(16.W))
    val valid       = Input (Bool())
    
    val delimeter   = Input (UInt(2.W))
    
    val active_lanes= Input (UInt(3.W))
    val sds_sent    = Input (Bool())
    
    val ll_tx_valid = Input (Bool())
    val link_data   = Output(UInt((numLanes*phyDataWidth).W))
    val link_idle   = Output(Bool())
    val ll_tx_state = Output(UInt(4.W))
  })
  
  addResource("/vsrc/slink_ll_tx.v")
  addResource("/vsrc/slink_ecc_syndrome.v")
}


class WlinkLLRx(val numLanes: Int, val phyDataWidth: Int, val width: Int)(implicit p: Parameters) extends LazyModule{
  
  val node = new WlinkRxSourceNode(
    Seq(WlinkLLSourceRxPortParameters(sources = Seq(WlinkLLSourceRxParameters(Seq(0x0), "WlinkLLRx")), width = width))
  )
  
  lazy val module = new LazyModuleImp(this) with RequireAsyncReset{
    val io = IO(new Bundle{
      val clk                   = Input (Bool())
      val reset                 = Input (Bool())
      val enable                = Input (Bool())
      val swi_short_packet_max  = Input (UInt(8.W))
      
      val active_lanes          = Input (UInt(3.W))
      
      //val swi_allow_ecc_corrected         = Input (Bool())
      //val swi_ecc_corrected_causes_reset  = Input (Bool())
      //val swi_ecc_corrupted_causes_reset  = Input (Bool())
      //val swi_crc_corrupted_causes_reset  = Input (Bool())

      //val sds_received                    = Input (Bool())
      //val ecc_corrected                   = Output(Bool())
      //val ecc_corrupted                   = Output(Bool())
      ////val crc_corrupted                   = Output(Bool())
      //val external_link_reset_condition   = Input (Bool())
      val link_reset_condition            = Output(Bool())
      
      val link_data   = Input (UInt((numLanes*phyDataWidth).W))
      val ll_rx_valid = Input (Bool())
      val ll_rx_state = Output(UInt(4.W))
    })
    
    //val ll_app = node.out.head._1
    val (ll_app, edgesOut)   = node.out.unzip
    println(s"wlinkllrx size: ${node.out.size}")
    
    val ll_rx = Module(new slink_ll_rx(numLanes, phyDataWidth, width))
    ll_rx.io.clk                  := io.clk
    ll_rx.io.reset                := io.reset
    ll_rx.io.enable               := io.enable
    ll_rx.io.swi_short_packet_max := io.swi_short_packet_max
    ll_rx.io.active_lanes         := io.active_lanes
    
    ll_rx.io.delimeter            := 0.U //TEMP!!!!!
    
    ll_rx.io.sds_received                     := true.B //SEE IF THIS WORKS
    ll_rx.io.swi_allow_ecc_corrected          := true.B
    ll_rx.io.swi_ecc_corrected_causes_reset   := false.B
    ll_rx.io.swi_ecc_corrupted_causes_reset   := true.B
    ll_rx.io.swi_crc_corrupted_causes_reset   := false.B
    
    ll_rx.io.external_link_reset_condition    := false.B
    
    ll_rx.io.link_data        := io.link_data
    ll_rx.io.ll_rx_valid      := io.ll_rx_valid
    
    io.ll_rx_state            := ll_rx.io.ll_rx_state
    
    
    for(i <- 0 until node.out.size){
      ll_app(i).sop          := ll_rx.io.sop
      ll_app(i).data_id      := ll_rx.io.data_id
      ll_app(i).word_count   := ll_rx.io.word_count
      ll_app(i).data         := ll_rx.io.app_data
      ll_app(i).valid        := ll_rx.io.valid
      //ll_app(i).crc_corrupt  := ll_rx.io.crc_corrupted
      ll_app(i).crc          := ll_rx.io.crc
    }
    
  
  }
}

class slink_ll_rx(
  numLanes      : Int,
  phyDataWidth  : Int,
  appDataWidth  : Int
  
) extends BlackBox (Map(
  "NUM_LANES"       -> numLanes,
  "DATA_WIDTH"      -> phyDataWidth,
  "APP_DATA_WIDTH"  -> appDataWidth
)) with HasBlackBoxResource{

  val io = IO(new Bundle{
    val clk         = Input (Bool())
    val reset       = Input (Bool())
    val enable      = Input (Bool())
    
    val swi_short_packet_max  = Input (UInt(8.W))
    
    val sop         = Output(Bool())
    val data_id     = Output(UInt(8.W))
    val word_count  = Output(UInt(16.W))
    val app_data    = Output(UInt(appDataWidth.W))
    val crc         = Output(UInt(16.W))
    val valid       = Output(Bool())
    
    val delimeter   = Input (UInt(2.W))
    
    val active_lanes= Input (UInt(3.W))
    
    val swi_allow_ecc_corrected         = Input (Bool())
    val swi_ecc_corrected_causes_reset  = Input (Bool())
    val swi_ecc_corrupted_causes_reset  = Input (Bool())
    val swi_crc_corrupted_causes_reset  = Input (Bool())
    
    val sds_received                    = Input (Bool())
    val ecc_corrected                   = Output(Bool())
    val ecc_corrupted                   = Output(Bool())
    val crc_corrupted                   = Output(Bool())
    val external_link_reset_condition   = Input (Bool())
    val link_reset_condition            = Output(Bool())
    
    val link_data   = Input (UInt((numLanes*phyDataWidth).W))
    val ll_rx_valid = Input (Bool())
    val ll_rx_state = Output(UInt(4.W))
  })
  
  addResource("/vsrc/slink_ll_rx.v")
  addResource("/vsrc/slink_ll_rx_pkt_filt.v")
  
}


/**
  *   CRC Generation
  *
  *   Polynomial x16 + x12 + x5 + x0 (Visual represenation below)
  *
  *          +------------------------+---------------------------------+
  *          |                        |                                 |
  *          |                        v                                 v
  *    -->x--+->15  14  13  12  11--->x--->10  9   8   7   6   5   4--->x---->3   2   1   0
  *       ^                                                                               |
  *       |                                                                               |
  *       +-------------------------------------------------------------------------------+
  */
class WlinkCrcGen(val width: Int, val dummyImport: Boolean = false) extends Module{
  val io = IO(new Bundle{
    val in = Input (UInt(width.W))
    val out= Output(UInt(16.W))
  })
  
  
  // We need to this be padded to the highest byte
  val numBytes = scala.math.ceil(width / 8.0).toInt
  
  val totalBits   = numBytes * 8
  val paddedData  = Wire(UInt(totalBits.W))
  val extraZeros  = Wire(UInt((totalBits-width).W))
  extraZeros      := 0.U
  paddedData      := Cat(extraZeros, io.in)
  
  
  // This is going to be out XOR variables for each entry, 16 total
  // What we plan to do is add in the XORing for each bit as a "shift" operation
  var crcMap = scala.collection.mutable.Map[Int, scala.collection.mutable.ListBuffer[Int]]()
  for (i <- 0 until 16){
    crcMap(i) = scala.collection.mutable.ListBuffer[Int]()
    // -1 will represent the initial SEED for this CRC, in this case the seed is 0xFFFF
    // so when a -1 is seen in the map it's viewed as a 1'b1
    crcMap(i) += -1
  }
  
  for(chunk16 <- 0 until numBytes){ //could probably just be a totalBits?
    for (chunkbit <- 0 until 8){
      
      // This is the new XOR input to the polynomial variables
      // It grabs the current "0" XOR chain, then appends the current data bit
      
      val newCrcIn : scala.collection.mutable.ListBuffer[Int] = crcMap(0).clone += ((chunk16*8)+chunkbit)      
      
      //println(s"${chunkbit}: NewCrc: ${newCrcIn}")
      
      // Here is where we are handling the XORing for the polynomial
      for (i <- 0 until 16){    //important for ordering
        i match {
          case 3  => crcMap(i) = newCrcIn ++ crcMap(i+1)
          case 10 => crcMap(i) = newCrcIn ++ crcMap(i+1)
          case 15 => crcMap(i) = newCrcIn
          case _  => crcMap(i) = crcMap(i+1)
        }
        
        // Remove all of the XOR variables that have more than one instance, since something XOR'ed with itself is 0
        // We don't want to use something like distinct because that just removes the duplicates but leaves one instance and we don't want that
        crcMap(i) = crcMap(i).groupBy(x=>x).filter(_._2.lengthCompare(1) == 0).keySet.to[scala.collection.mutable.ListBuffer]
      }
    }
  }
  
  
  val crcCalc = Wire(Vec(16, Bool()))
  // This is where we will create the XORing for each crc output bit
  val xorList = Seq.tabulate(16){i => Wire(Vec(crcMap(i).size, Bool()))}
  
  for(i <- 0 until 16){
    var bindex = 0
    crcMap(i).foreach{ j => 
      if(j != -1) {
        xorList(i)(bindex) := paddedData(j).asBool //io.in(j).asBool
      } else {
        xorList(i)(bindex) := true.B
      }
      bindex += 1
    }
    
    crcCalc(i) := xorList(i).reduce(_^_)
    
  }
  
  io.out := crcCalc.asUInt
  
  
}


object WlinkCrcGen{
  def apply[T <: Data](in: T): UInt = {
    val crcgen = Module(new WlinkCrcGen(in.getWidth))
    crcgen.io.in := in
    crcgen.io.out
  }
}



// object WlinkCrcGenGen extends App {  
//   implicit val p: Parameters = new BaseXbarConfig
//   
//   val verilog = (new ChiselStage).emitVerilog(
//     new WlinkCrcGen(72),
//      
//     //args
//     Array("--target-dir", "output/")//, "--no-dce")
//   )
// }

