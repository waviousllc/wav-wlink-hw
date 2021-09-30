package wav.wlink

import wav.common._

//import Chisel._
import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum
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

/*
.rst_start

.rst_end
*/


object WlinkGenericFCState extends ChiselEnum{
  val IDLE            = Value(0.U)
  val SEND_CREDITS1   = Value(1.U) 
  val SEND_CREDITS2   = Value(2.U) 
  val LINK_EN_WAIT    = Value(3.U) 
  val LINK_IDLE       = Value(4.U) 
  val LINK_DATA       = Value(5.U) 
  val SEND_ACK        = Value(6.U) 
  val SEND_NACK       = Value(7.U)
}

class WlinkGenericFCSM(
  baseAddr          : BigInt,
  a2lDataWidth      : Int,
  a2lDepth          : Int,
  l2aDataWidth      : Int,
  l2aDepth          : Int,
  channelName       : String,
  dataIdDefault     : Int = 0x40,
  crIdDefault       : Int = 0x10,
  crackIdDefault    : Int = 0x11,
  ackIdDefault      : Int = 0x12,
  nackIdDefault     : Int = 0x13,
  noRegTest         : Boolean = false
)(implicit p: Parameters) extends LazyModule{
  
  val a2lAddrWidth  = log2Up(a2lDepth)
  val l2aAddrWidth  = log2Up(l2aDepth)
  
  //val txWlinkDataWidth = a2lDataWidth + 8
  //val rxWlinkDataWidth = l2aDataWidth + 8
  
  //Need to ork on bytes so round up to nearest byte
  val txWlinkDataWidth = ((a2lDataWidth + 8)/8.0).ceil.toInt * 8
  val rxWlinkDataWidth = ((l2aDataWidth + 8)/8.0).ceil.toInt * 8
  
  val wordCountSize    = (txWlinkDataWidth/8.0).ceil.toInt.asUInt     //The 8.0 is super important!
  
  
  val txnode  = new WlinkTxSourceNode(Seq(WlinkLLSourceTxPortParameters(sources = Seq(WlinkLLSourceTxParameters(Seq(0x0), s"${channelName}_tx")), width = txWlinkDataWidth)))
  
  val rxnode  = new WlinkRxSinkNode(Seq(WlinkLLSinkRxPortParameters(sinks = Seq(WlinkLLSinkRxParameters(Seq(0x0), s"${channelName}_rx")), width = rxWlinkDataWidth)))
  
  val device = new SimpleDevice("wlinkfcsm", Seq("wavious,wlinkfcsm"))
  val node = WavAPBRegisterNode(
    //address = AddressSet(baseAddr, 0xff),
    address = AddressSet.misaligned(baseAddr, 0x100),
    device  = device,
    //concurrency = 1, 
    beatBytes = 4,
    noRegTest = noRegTest)
  
  
  override lazy val module = new LazyModuleImp(this) {//with RequireAsyncReset {
    val io = IO(new Bundle{
      val app = new Bundle{
        val clk         = Input (Bool())
        val reset       = Input (Bool())
        val enable      = Input (Bool())
        val a2l_valid   = Input (Bool())
        val a2l_data    = Input (UInt(a2lDataWidth.W))
        val a2l_ready   = Output(Bool())
        
        val l2a_valid   = Output(Bool())
        val l2a_data    = Output(UInt(l2aDataWidth.W))
        val l2a_accept  = Input (Bool())
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
    
    
    val ll_tx       = txnode.out.head._1
    val ll_rx       = rxnode.in.head._1
    
    val ll_rx_data  = ll_rx.data(rxWlinkDataWidth-1, 8) //data minus the packet number
    val ll_rx_pktnum= ll_rx.data(7, 0)
    
    
    val cr_id         = Wire(UInt(8.W))
    val crack_id      = Wire(UInt(8.W))
    val ack_id        = Wire(UInt(8.W))
    val nack_id       = Wire(UInt(8.W))
    val swi_data_id   = Wire(UInt(8.W))
    val link_en_wait  = Wire(UInt(8.W))
    val ack_dly_count = Wire(UInt(8.W))
    
    val disable_crc   = Wire(Bool())
    
    
    val ne_tx_credit_max    = Wire(UInt(8.W))
    ne_tx_credit_max        := Fill(a2lAddrWidth+1, "b1".U)
    val ne_rx_credit_max    = Wire(UInt(8.W))
    ne_rx_credit_max        := Fill(l2aAddrWidth+1, "b1".U)
    
    val nstate    = WireInit(WlinkGenericFCState.IDLE)
    val state     = withClockAndReset(io.tx.clk.asClock, io.tx.reset.asAsyncReset){RegNext(next=nstate, init=WlinkGenericFCState.IDLE)}
    
    
    
    //------------------------------------
    // Receive Side
    //------------------------------------    
    //UNTIL I CAN FIX THE WIDTH IN DIPLOMACY
    //val rxcrcgen = Module(new WlinkCrcGen(rxWlinkDataWidth))
    //rxcrcgen.io.in                := ll_rx.data
    
    //val rx_crc_computed           = rxcrcgen.io.out//WlinkCrcGen(ll_rx.data)
    val rx_crc_computed           = WlinkCrcGen(ll_rx.data)
    
    val crc_corrupt               = Mux((ll_rx.sop && ll_rx.valid && (ll_rx.data_id === swi_data_id)) & ~disable_crc, (rx_crc_computed =/= ll_rx.crc), false.B)
    //val crc_corrupt               = Mux(~disable_crc, (rx_crc_computed =/= ll_rx.crc), false.B)
    val crc_errors_in             = Wire(UInt(16.W))
    val crc_errors                = withClockAndReset(io.rx.clk.asClock, io.rx.reset.asAsyncReset){RegNext(crc_errors_in, 0.U)}
    io.rx.crc_err                 := crc_errors.orR
    
    
    val valid_rx_pkt              = ll_rx.sop && ll_rx.valid 
    
    val pkt_is_data_pkt           = valid_rx_pkt && (ll_rx.data_id === swi_data_id) && ~crc_corrupt
    val valid_rx_pkt_crc_err      = valid_rx_pkt && (ll_rx.data_id === swi_data_id) &&  crc_corrupt
    
    val pkt_is_cr_pkt             = valid_rx_pkt && (ll_rx.data_id === cr_id)
    val pkt_is_crack_pkt          = valid_rx_pkt && (ll_rx.data_id === crack_id)
    val pkt_is_ack_pkt            = valid_rx_pkt && (ll_rx.data_id === ack_id)
    val pkt_is_nack_pkt           = valid_rx_pkt && (ll_rx.data_id === nack_id)
    
    
    
    val exp_pkt_seen              = Wire(Bool())
    val exp_pkt_not_seen          = Wire(Bool())
    val last_good_pkt_in          = Wire(UInt(8.W))
    val last_good_pkt             = withClockAndReset(io.rx.clk.asClock, io.rx.reset.asAsyncReset){RegNext(last_good_pkt_in, 0.U)}
    
    
    val fe_rx_credit_max_in       = Wire(UInt(8.W))
    val fe_rx_credit_max          = withClockAndReset(io.rx.clk.asClock, io.rx.reset.asAsyncReset){RegNext(fe_rx_credit_max_in, 0.U)}
    
    val cr_pkt_seen_rx_in         = Wire(Bool())
    val cr_pkt_seen_rx            = withClockAndReset(io.rx.clk.asClock, io.rx.reset.asAsyncReset){RegNext(cr_pkt_seen_rx_in, false.B)}
    val crack_pkt_seen_rx_in      = Wire(Bool())
    val crack_pkt_seen_rx         = withClockAndReset(io.rx.clk.asClock, io.rx.reset.asAsyncReset){RegNext(crack_pkt_seen_rx_in, false.B)}
    
    
    withClockAndReset(io.rx.clk.asClock, io.rx.reset.asAsyncReset){
      val exp_pkt_num_in          = Wire(UInt(8.W))
      val exp_pkt_num             = RegNext(exp_pkt_num_in, 0.U)
      val en_ff2_rx               = WavDemetReset(io.app.enable)
      
      crc_errors_in               := Mux(en_ff2_rx, Mux(crc_corrupt, Mux(crc_errors === "hffff".U, crc_errors, crc_errors + 1.U), crc_errors), 0.U)
      
      // The far end TX credit max is only used in the RX domain for the next expected packet rollover
      val fe_tx_credit_max_in     = Wire(UInt(8.W))
      val fe_tx_credit_max        = RegNext(fe_tx_credit_max_in, 0.U)
      fe_tx_credit_max_in         := Mux(~en_ff2_rx, 0.U, Mux(pkt_is_cr_pkt || pkt_is_crack_pkt, ll_rx.word_count(7, 0), fe_tx_credit_max))
      
      // Far end RX credit check
      fe_rx_credit_max_in         := Mux(~en_ff2_rx, 0.U, Mux(pkt_is_cr_pkt || pkt_is_crack_pkt, ll_rx.word_count(15, 8), fe_rx_credit_max))
      
      // Capture when we see a cr/cr_ack packet and update. This is synchronized to the far side
      cr_pkt_seen_rx_in           := Mux(~en_ff2_rx, false.B, Mux(pkt_is_cr_pkt,    true.B, cr_pkt_seen_rx))
      crack_pkt_seen_rx_in        := Mux(~en_ff2_rx, false.B, Mux(pkt_is_crack_pkt, true.B, crack_pkt_seen_rx))
      
      exp_pkt_seen                := pkt_is_data_pkt && (ll_rx_pktnum === exp_pkt_num)
      exp_pkt_not_seen            := pkt_is_data_pkt && (ll_rx_pktnum =/= exp_pkt_num)      
      exp_pkt_num_in              := Mux(~en_ff2_rx, 0.U, Mux(exp_pkt_seen, Mux(exp_pkt_num === fe_tx_credit_max, 0.U, exp_pkt_num + 1.U), exp_pkt_num))
      last_good_pkt_in            := Mux(~en_ff2_rx, 0.U, Mux(exp_pkt_seen, exp_pkt_num, last_good_pkt))
    }
    
    
    // The FC block in this case is "reversed", meaning the APP connects to the link clk, and vice versa, We may make a cleaner version in the future.
    // The replay features are disabled in this case, since it's not really possible to use
    val l2a_fc_replay             = Module(new WlinkGenericFCReplayV2(l2aDataWidth, l2aDepth, s"${channelName}_l2a"))
    l2a_fc_replay.app.clk         := io.rx.clk//.asClock
    l2a_fc_replay.app.reset       := io.rx.reset
    l2a_fc_replay.app.enable      := io.app.enable
    l2a_fc_replay.app.data        := ll_rx_data
    l2a_fc_replay.app.valid       := exp_pkt_seen
    
    
    l2a_fc_replay.link.clk        := io.app.clk//.asClock
    l2a_fc_replay.link.reset      := io.app.reset
    
    io.app.l2a_valid              := l2a_fc_replay.link.valid
    io.app.l2a_data               := l2a_fc_replay.link.data
    l2a_fc_replay.link.advance    := io.app.l2a_accept
    
    l2a_fc_replay.link.ack_update := true.B
    l2a_fc_replay.link.revert_addr:= 0.U
    l2a_fc_replay.link.revert     := false.B
    l2a_fc_replay.link.ack_addr   := l2a_fc_replay.link.cur_addr
    
    // Sends the current L2A read address to the TX domain for updating the far end on ACK/NACK
    val l2a_fifo_raddr_txclk      = Wire(UInt((l2aAddrWidth+1).W))
    val l2a_fifo_addr_to_tx       = Module(new WlinkGenericFCReplayAddrSync(l2aAddrWidth+1))
    l2a_fifo_addr_to_tx.w.clk     := io.app.clk.asClock
    l2a_fifo_addr_to_tx.w.reset   := io.app.reset
    l2a_fifo_addr_to_tx.w.inc     := true.B                                                         //???? Check on this
    l2a_fifo_addr_to_tx.w.addr    := l2a_fc_replay.link.cur_addr
  
    l2a_fifo_addr_to_tx.r.clk     := io.tx.clk.asClock
    l2a_fifo_addr_to_tx.r.reset   := io.tx.reset
    l2a_fifo_raddr_txclk          := l2a_fifo_addr_to_tx.r.addr
    
    
    //------------------------------------
    // RX -> TX ACK/NACK reception
    // Deciding to ship the ACK/NACKs from the RX to the TX through a FIFO. Not sure there is a
    // better way. This allows the RX data path to not have any additional latency, just the ACK/NACK
    // which are still pretty sparse as it is
    //------------------------------------
    
    //val update_ack_nack_fifo    = Wire(Bool())
    val ack_nack_fifo           = Module(new WavFIFO(dataWidth=16+3, addrWidth=3))  //Addr may need to be the same as the L2A
    val ack_nack_fifo_valid     = ~ack_nack_fifo.io.rempty
    ack_nack_fifo.io.wclk       := io.rx.clk
    ack_nack_fifo.io.wreset     := io.rx.reset
    ack_nack_fifo.io.winc       := pkt_is_ack_pkt || pkt_is_nack_pkt || exp_pkt_seen || exp_pkt_not_seen || valid_rx_pkt_crc_err
    
    // Things we need to send from the RX to the TX
    // ACK Reception (to update Tx link addr) - Send WC
    // NACK Reception (to update Tx link addr) - Send WC
    // Good Packet reception with Last Good Packet Sent (for sending ACK to far side transmitter) - Send Last Good packet
    // Exp Packet Not seen - Send Last Good Packet
    // CRC Errors in which the last good packt is still valid - Send last good packet
    // 
    val pkttypenotifier         = Wire(UInt(3.W))
    pkttypenotifier             := Mux(pkt_is_ack_pkt,  2.U, 
                                    Mux(pkt_is_nack_pkt, 3.U, 
                                      Mux(valid_rx_pkt_crc_err, 4.U, 
                                        Mux(exp_pkt_not_seen, 1.U, 0.U))))
    ack_nack_fifo.io.wdata      := Mux(pkt_is_ack_pkt || pkt_is_nack_pkt,       
                                      Cat(pkttypenotifier, ll_rx.word_count),
                                      Cat(pkttypenotifier, Cat(0.U(8.W), last_good_pkt_in)))
    
    ack_nack_fifo.io.rclk       := io.tx.clk
    ack_nack_fifo.io.rreset     := io.tx.reset
    ack_nack_fifo.io.rinc       := ack_nack_fifo_valid && (state =/= WlinkGenericFCState.IDLE)
    
    //TODO: Could I merge the crc error and NotExp as the same one to save on the FIFO size?
    val isExpPacket             = ack_nack_fifo.io.rdata(18,16) === 0.U && ack_nack_fifo_valid
    val isNotExpPacket          = ack_nack_fifo.io.rdata(18,16) === 1.U && ack_nack_fifo_valid
    val isAckPacket             = ack_nack_fifo.io.rdata(18,16) === 2.U && ack_nack_fifo_valid
    val isNackPacket            = ack_nack_fifo.io.rdata(18,16) === 3.U && ack_nack_fifo_valid
    val crcCorruptSeen          = ack_nack_fifo.io.rdata(18,16) === 4.U && ack_nack_fifo_valid
    
    
    //------------------------------------
    // TX Clock Domain
    //------------------------------------
    val a2l_fc_replay             = Module(new WlinkGenericFCReplayV2(a2lDataWidth, a2lDepth, s"${channelName}_a2l"))
    a2l_fc_replay.app.clk         := io.app.clk//.asClock
    a2l_fc_replay.app.reset       := io.app.reset
    a2l_fc_replay.app.enable      := io.app.enable
    a2l_fc_replay.app.data        := io.app.a2l_data
    a2l_fc_replay.app.valid       := io.app.a2l_valid
    io.app.a2l_ready              := a2l_fc_replay.app.ready
    a2l_fc_replay.link.clk        := io.tx.clk//.asClock
    a2l_fc_replay.link.reset      := io.tx.reset
    //REMOVE THIS CONNECTION
//     a2l_fc_replay.link.ack_update := true.B
//     a2l_fc_replay.link.ack_addr   := a2l_fc_replay.link.cur_addr
//     a2l_fc_replay.link.revert     := false.B
//     a2l_fc_replay.link.revert_addr:= 0.U
    
    
    
    withClockAndReset(io.tx.clk.asClock, io.tx.reset.asAsyncReset){
      val en_ff2_tx                   = WavDemetReset(io.app.enable)
      
      val sop_in                      = Wire(Bool())
      val sop                         = RegNext(sop_in, false.B)
      val data_id_in                  = Wire(UInt(8.W))
      val data_id                     = RegNext(data_id_in, 0.U)
      val word_count_in               = Wire(UInt(16.W))
      val word_count                  = RegNext(word_count_in, 0.U)
      val link_data_in                = Wire(UInt(txWlinkDataWidth.W))
      val link_data                   = RegNext(link_data_in, 0.U)
      
      //val crc_data_computed           = WlinkCrcGen(link_data_in)   //We will take this only when valid
      //val crc_data_in                 = Wire(UInt(16.W))
      //val crc_data                    = RegNext(crc_data_in, 0.U)   
      
      val count_in                    = Wire(UInt(8.W))
      val count                       = RegNext(count_in, 0.U)
      
      val link_ack_addr               = Wire(UInt((a2lAddrWidth+1).W))
      link_ack_addr                   := ack_nack_fifo.io.rdata(a2lAddrWidth, 0)

      val link_ack_update             = isAckPacket
      val link_revert                 = isNackPacket
      val link_revert_addr            = Wire(UInt((a2lAddrWidth+1).W))
      
      val ack_seen_before             = RegInit(false.B)
      ack_seen_before                 := Mux((state === WlinkGenericFCState.IDLE), false.B, Mux(link_ack_update, true.B, ack_seen_before))
      
      //We will get back the last good packet, so we need to do the last good +1
      //There is an issue however where if you get an error on the first packet and the far end sends
      //back a NACK, pkt number 0 is really what you need to replay, not 1. So ensure that we
      //know the far end has received at least one packet sucessfully to ensure we don't mess up the sequence
      link_revert_addr                := Mux(~ack_seen_before, 0.U,
                                           Mux((ack_nack_fifo.io.rdata(a2lAddrWidth, 0) === ne_tx_credit_max), 0.U, (ack_nack_fifo.io.rdata(a2lAddrWidth, 0) + 1.U)))
      
      val link_cur_addr_8bit          = Wire(UInt(8.W))
      link_cur_addr_8bit              := a2l_fc_replay.link.cur_addr
      
      
      a2l_fc_replay.link.ack_update   := link_ack_update
      a2l_fc_replay.link.ack_addr     := link_ack_addr
      a2l_fc_replay.link.revert       := link_revert
      a2l_fc_replay.link.revert_addr  := link_revert_addr
      
      // Top byte of the Ack/Nack is the current Far end RX pointer
      // This does not change with Ack/Nack so we can let it ride with either
      val fe_rx_ptr_in                = Wire(UInt(8.W))
      val fe_rx_ptr                   = RegNext(fe_rx_ptr_in, 0.U)
      fe_rx_ptr_in                    := Mux(~en_ff2_tx, 0.U, Mux(link_ack_update || link_revert, ack_nack_fifo.io.rdata(15,8), fe_rx_ptr))
      
      val ne_rx_ptr_in                = Wire(UInt(8.W))
      val ne_rx_ptr                   = RegNext(ne_rx_ptr_in, 0.U)
      val ne_rx_ptr_next              = Wire(UInt(8.W))
      ne_rx_ptr_next                  := Mux((ne_rx_ptr === fe_rx_credit_max), 0.U, ne_rx_ptr + 1.U)
      
      val fe_rx_is_full               = Wire(Bool())
      val fe_rx_ptr_msb               = Wire(UInt(3.W))
      
      
      fe_rx_ptr_msb                   := 0.U
      for(i <- 0 until 8){
        when(fe_rx_credit_max(i)){
          fe_rx_ptr_msb               := i.asUInt
        }
      }
      
      // Default to FULL and check for conditions that aren't full
      // msb's should be != and all lower bits == for full
      // See about optimizing this? I think we can get one more entry
      fe_rx_is_full                   := true.B
      for(i <- 0 until 8){
        when(i.asUInt < fe_rx_ptr_msb){
          when(ne_rx_ptr_next(i) =/= fe_rx_ptr(i)){
            fe_rx_is_full               := false.B
          }
        }
      }
      
      when(ne_rx_ptr_next(fe_rx_ptr_msb) === fe_rx_ptr(fe_rx_ptr_msb)){
        fe_rx_is_full                 := false.B
      }
      
      
      
      
      val l2a_fifo_raddr_txclk_prev   = RegNext(l2a_fifo_raddr_txclk, 0.U)
      val l2a_fifo_raddr_txclk_update = l2a_fifo_raddr_txclk_prev =/= l2a_fifo_raddr_txclk
      
      val cr_pkt_seen_tx              = WavDemetReset(cr_pkt_seen_rx)
      val crack_pkt_seen_tx           = WavDemetReset(crack_pkt_seen_rx)
      
      val send_ack_req_in             = Wire(Bool())
      val send_ack_req                = RegNext(send_ack_req_in, false.B)
      val send_nack_req_in            = Wire(Bool())
      val send_nack_req               = RegNext(send_nack_req_in, false.B)
      
      //Update the last GOOD packet from the RX whenever we see it come in
      //Note: Even thought NotExpPacket will send the last good packet, we don't need to update in this case.
      val last_good_pkt_from_rx_in    = Wire(UInt(8.W))
      val last_good_pkt_from_rx       = RegNext(last_good_pkt_from_rx_in, 0.U)
      last_good_pkt_from_rx_in        := Mux(~en_ff2_tx, 0.U, Mux(isExpPacket, ack_nack_fifo.io.rdata(7, 0), last_good_pkt_from_rx))
      
      val last_ack_pkt_sent_in        = Wire(UInt(8.W))
      val last_ack_pkt_sent           = RegNext(last_ack_pkt_sent_in, 0.U)
      
      
      
      
      
      nstate                          := state
      count_in                        := count
      
      sop_in                          := sop
      data_id_in                      := data_id
      word_count_in                   := word_count
      link_data_in                    := link_data
      //crc_data_in                     := crc_data
      
      
      ne_rx_ptr_in                    := ne_rx_ptr
      
      last_ack_pkt_sent_in            := last_ack_pkt_sent
      
      send_ack_req_in                 := Mux(send_ack_req,  true.B, (isExpPacket || l2a_fifo_raddr_txclk_update))
      send_nack_req_in                := Mux(send_nack_req, true.B, (crcCorruptSeen || isNotExpPacket))
      
      a2l_fc_replay.link.advance      := false.B
      
      
      when(state === WlinkGenericFCState.IDLE){
        //------------------------------------
        last_ack_pkt_sent_in          := 0.U
        ne_rx_ptr_in                  := 0.U
        
        when(en_ff2_tx){
          sop_in                      := true.B
          data_id_in                  := cr_id
          word_count_in               := Cat(ne_rx_credit_max, ne_tx_credit_max)
          link_data_in                := 0.U
          nstate                      := WlinkGenericFCState.SEND_CREDITS1
        }
      
      }.elsewhen(state === WlinkGenericFCState.SEND_CREDITS1){
        //------------------------------------
        when(ll_tx.advance){
          when(crack_pkt_seen_tx || cr_pkt_seen_tx){
            sop_in                    := true.B
            data_id_in                := crack_id
            word_count_in             := Cat(ne_rx_credit_max, ne_tx_credit_max)
            link_data_in              := 0.U
            nstate                    := WlinkGenericFCState.SEND_CREDITS2
          }.otherwise{
            sop_in                    := true.B
            data_id_in                := cr_id
            word_count_in             := Cat(ne_rx_credit_max, ne_tx_credit_max)
            link_data_in              := 0.U
          }
        }
      }.elsewhen(state === WlinkGenericFCState.SEND_CREDITS2){
        //------------------------------------
        when(ll_tx.advance){
          when(crack_pkt_seen_tx){
            sop_in                    := false.B
            data_id_in                := 0.U
            word_count_in             := 0.U
            link_data_in              := 0.U
            count_in                  := link_en_wait
            nstate                    := WlinkGenericFCState.LINK_EN_WAIT
          }.otherwise{
            sop_in                    := true.B
            data_id_in                := crack_id
            word_count_in             := Cat(ne_rx_credit_max, ne_tx_credit_max)
            link_data_in              := 0.U
          }
        }
      
      }.elsewhen(state === WlinkGenericFCState.LINK_EN_WAIT){
        //------------------------------------
        // Hold here to ensure credits in the receive domain have had time to settle
        // from a CDC perspective. This is just to keep down on the number of FIFOs/syncs
        when(count === 0.U){
          nstate                      := WlinkGenericFCState.LINK_IDLE
        }.otherwise{
          count_in                    := count - 1.U
        }
      
      }.elsewhen(state === WlinkGenericFCState.LINK_IDLE){
        //------------------------------------
        // Priority is NACK -> ACK -> DATA
        count_in                      := Mux(count === 0.U, 0.U, count - 1.U)
        when(send_nack_req){
          last_ack_pkt_sent_in        := last_good_pkt_from_rx_in 
          send_nack_req_in            := false.B                    //Clear this now to protect against missing another NACK request
          sop_in                      := true.B
          data_id_in                  := nack_id
          word_count_in               := Cat(l2a_fifo_raddr_txclk, last_ack_pkt_sent_in)
          link_data_in                := 0.U
          nstate                      := WlinkGenericFCState.SEND_NACK
          
        }.elsewhen(send_ack_req && count === 0.U){
          last_ack_pkt_sent_in        := last_good_pkt_from_rx_in   
          send_ack_req_in             := false.B                    //Clear this now to protect against missing another ACK request
          sop_in                      := true.B
          data_id_in                  := ack_id
          word_count_in               := Cat(l2a_fifo_raddr_txclk, last_ack_pkt_sent_in)
          link_data_in                := 0.U
          nstate                      := WlinkGenericFCState.SEND_ACK
          
        }.elsewhen(a2l_fc_replay.link.valid && ~fe_rx_is_full){
          a2l_fc_replay.link.advance  := true.B   
          sop_in                      := true.B
          data_id_in                  := swi_data_id
          word_count_in               := wordCountSize
          link_data_in                := Cat(a2l_fc_replay.link.data, link_cur_addr_8bit)
          //crc_data_in                 := crc_data_computed
          ne_rx_ptr_in                := ne_rx_ptr_next
          nstate                      := WlinkGenericFCState.LINK_DATA
        }
      
      }.elsewhen(state === WlinkGenericFCState.LINK_DATA){
        //------------------------------------
        count_in                      := Mux(count === 0.U, 0.U, count - 1.U)
        when(ll_tx.advance){
          when(send_nack_req){
            last_ack_pkt_sent_in      := last_good_pkt_from_rx_in 
            send_nack_req_in          := false.B                    
            sop_in                    := true.B
            data_id_in                := nack_id
            word_count_in             := Cat(l2a_fifo_raddr_txclk, last_ack_pkt_sent_in)
            link_data_in              := 0.U
            nstate                    := WlinkGenericFCState.SEND_NACK
          }.elsewhen(send_ack_req && count === 0.U){
            last_ack_pkt_sent_in      := last_good_pkt_from_rx_in   
            send_ack_req_in           := false.B                    //Clear this now to protect against missing another ACK request
            sop_in                    := true.B
            data_id_in                := ack_id
            word_count_in             := Cat(l2a_fifo_raddr_txclk, last_ack_pkt_sent_in)
            link_data_in              := 0.U
            nstate                    := WlinkGenericFCState.SEND_ACK
            
          }.elsewhen(a2l_fc_replay.link.valid && ~fe_rx_is_full){
            a2l_fc_replay.link.advance:= true.B   
            sop_in                    := true.B
            data_id_in                := swi_data_id
            word_count_in             := wordCountSize
            link_data_in              := Cat(a2l_fc_replay.link.data, link_cur_addr_8bit)
            //crc_data_in               := crc_data_computed
            ne_rx_ptr_in              := ne_rx_ptr_next
            nstate                    := WlinkGenericFCState.LINK_DATA
          }.otherwise{
            sop_in                    := false.B
            nstate                    := WlinkGenericFCState.LINK_IDLE
          }
        }
        
      
      }.elsewhen(state === WlinkGenericFCState.SEND_NACK){
        //------------------------------------
        when(ll_tx.advance){
          sop_in                      := false.B
          nstate                      := WlinkGenericFCState.LINK_IDLE
        }
      
      }.elsewhen(state === WlinkGenericFCState.SEND_ACK){
        //------------------------------------
        // Reset the count in this case. This counter reduces the freq of ACKs being
        // sent to give more bandwidth to the real data
        when(ll_tx.advance){
          //sop_in                      := false.B
          count_in                    := ack_dly_count
          //nstate                      := WlinkGenericFCState.LINK_IDLE
          when(send_nack_req){
            last_ack_pkt_sent_in      := last_good_pkt_from_rx_in 
            send_nack_req_in          := false.B                    
            sop_in                    := true.B
            data_id_in                := nack_id
            word_count_in             := Cat(l2a_fifo_raddr_txclk, last_ack_pkt_sent_in)
            link_data_in              := 0.U
            nstate                    := WlinkGenericFCState.SEND_NACK
          }.elsewhen(a2l_fc_replay.link.valid && ~fe_rx_is_full){
            a2l_fc_replay.link.advance:= true.B   
            sop_in                    := true.B
            data_id_in                := swi_data_id
            word_count_in             := wordCountSize
            link_data_in              := Cat(a2l_fc_replay.link.data, link_cur_addr_8bit)
            //crc_data_in               := crc_data_computed
            ne_rx_ptr_in              := ne_rx_ptr_next
            nstate                    := WlinkGenericFCState.LINK_DATA
          }.otherwise{
            sop_in                    := false.B
            nstate                    := WlinkGenericFCState.LINK_IDLE
          }
        }
        
      }.otherwise{
        //------------------------------------
        // Default
        nstate                      := WlinkGenericFCState.IDLE
      }
      
      when(link_revert){
        ne_rx_ptr_in                := ack_nack_fifo.io.rdata(15,8)
      }
      
      when(~en_ff2_tx){
        nstate                      := WlinkGenericFCState.IDLE
      }
    
    
      ll_tx.sop         := sop
      ll_tx.data_id     := data_id
      ll_tx.word_count  := word_count
      ll_tx.data        := link_data
      //ll_tx.crc         := crc_data
      //val crc_data_computed           = WlinkCrcGen(link_data_in)
      ll_tx.crc         := WlinkCrcGen(ll_tx.data)
      
    }//tx clk/reset domain
    
    
    
    
    node.regmap(
      WavSWReg(0x00, "IDControl", "",
        WavRW(cr_id,          crIdDefault.asUInt,        "cr_id",            "Credit Packet Data ID"),
        WavRW(crack_id,       crackIdDefault.asUInt,     "crack_id",         "Credit Ack Packet Data ID"),
        WavRW(ack_id,         ackIdDefault.asUInt,       "ack_id",           "Ack Packet Data ID"),
        WavRW(nack_id,        nackIdDefault.asUInt,      "nack_id",          "Nack Packet Data ID")),
      
      WavSWReg(0x04, "DataIDControl", "",
        WavRW(swi_data_id,    dataIdDefault.asUInt,      "data_id",          "Data Packet Data ID")),
      
      WavSWReg(0x08, "TxFCFifo", "",
        WavRO(a2l_fc_replay.link.empty,                  "empty",            "ApplicationToLink Layer Flow Control buffer empty status")),
      
      WavSWReg(0x10, "AckNackFifo", "",
        WavRO(ack_nack_fifo.io.rempty,                            "empty",                 ""),
        WavRO(ack_nack_fifo.io.wfull,                             "full",                  ""),
        WavRO(ack_nack_fifo.io.half_full,                         "half_full",             ""),
        WavRO(ack_nack_fifo.io.almost_empty,                      "almost_empty_status",   ""),
        WavRO(ack_nack_fifo.io.almost_full,                       "almost_full_status",    ""),
        //WavRO(ack_nack_fifo.io.half_full,                         "half_full",             ""),
        WavRW(ack_nack_fifo.io.swi_almost_full,         6.U,      "almost_full",           ""),
        WavRW(ack_nack_fifo.io.swi_almost_empty,        2.U,      "almost_empty",          "")),
      
      WavSWReg(0x14, "SMControl", "",
        WavRW(link_en_wait,        8.U,       "link_en_wait",         "Number of cycles to remain IDLE after credit negotiation"),
        WavRW(ack_dly_count,       7.U,       "ack_dly_count",        "Number of cycles to wait between ACK packets. "),
        WavRW(disable_crc,         false.B,   "disable_crc",          "Disable CRC check")),
        
      WavSWReg(0x20, "CRCErrors", "",
        WavRO(crc_errors_in,                            "crc_errors",                 "Number of CRC errors seen"))  //why can I not use the reg here???
    )
    
    println(s"Default Packet numbers for ${channelName}")
    println(s"  Credit ID    : ${crIdDefault}")
    println(s"  Credit Ack ID: ${crackIdDefault}")
    println(s"  Ack ID       : ${ackIdDefault}")
    println(s"  Nack ID      : ${nackIdDefault}")
    println(s"  Data ID      : ${dataIdDefault}")
  }
}



/**
  *   Generic FC Replay block. Based on the S-Link version 
  *
  *   Using RawModule so user has to be explicit on the clocking/resets
  */
class WlinkGenericFCReplayV2(dataWidth: Int, depth: Int, name: String)(implicit p: Parameters) extends RawModule{
  
  val addrWidth = log2Up(depth)
  
  val app = IO(new Bundle{
    val clk         = Input (Bool())
    val reset       = Input (Bool())
    val enable      = Input (Bool())
    
    val data        = Input (UInt(dataWidth.W))
    val valid       = Input (Bool())
    val ready       = Output(Bool())
  })
  
  val link = IO(new Bundle{
    val clk         = Input (Bool())
    val reset       = Input (Bool())
    val ack_update  = Input (Bool())
    val ack_addr    = Input (UInt((addrWidth+1).W))
    val revert      = Input (Bool())
    val revert_addr = Input (UInt((addrWidth+1).W))
    
    val cur_addr    = Output(UInt((addrWidth+1).W))
    val data        = Output(UInt(dataWidth.W))
    val valid       = Output(Bool())
    val advance     = Input (Bool())
    
    val empty       = Output(Bool())
  })
  
  
  //--------------------------------
  // App Clock Domain
  // Application address is the write address of the FIFO (wbin_ptr)
  val enable_app_clk  = withClockAndReset(app.clk.asClock, app.reset.asAsyncReset){WavDemetReset(app.enable)}
  
  val a2l_link_addr_app_clk       = Wire(UInt((addrWidth+1).W))
  
  val a2l_app_addr    = Wire(UInt((addrWidth+1).W))
  
  val a2l_full        = (a2l_app_addr(addrWidth) =/= a2l_link_addr_app_clk(addrWidth)) && (a2l_app_addr(addrWidth-1,0) === a2l_link_addr_app_clk(addrWidth-1,0))
  app.ready           := ~a2l_full && enable_app_clk
  val a2l_write       = app.ready && app.valid
  
  //--------------------------------
  // Link Clock Domain
  
  val enable_link_clk  = withClockAndReset(link.clk.asClock, link.reset.asAsyncReset){WavDemetReset(app.enable)}
  
  
  //--------------------------------
  val fifo_empty                = Wire(Bool())
  link.empty                    := fifo_empty
  link.valid                    := Mux(enable_link_clk, ~link.empty, false.B)
  val a2l_read                  = link.valid && link.advance
  
  val fifo = Module(new WavFIFO(dataWidth=dataWidth, addrWidth=addrWidth, name=name, withReplay=true))
  fifo.io.wclk          := app.clk
  fifo.io.wreset        := app.reset
  fifo.io.winc          := a2l_write
  fifo.io.wdata         := app.data
  
  a2l_app_addr          := fifo.io.wbin_ptr
  
  fifo.io.rclk          := link.clk
  fifo.io.rreset        := link.reset
  fifo.io.rinc          := a2l_read
  link.data             := fifo.io.rdata
  fifo_empty            := fifo.io.rempty
  
  fifo.io.rrevert.get       := link.revert
  fifo.io.rrevert_addr.get  := link.revert_addr
  
  fifo.io.swi_almost_empty  := 1.U
  fifo.io.swi_almost_full   := (depth-1).asUInt
  
  link.cur_addr         := fifo.io.rbin_ptr
  
  //--------------------------------
  // Sending link address to app clock
  // Here we send the most recently ACKnowledged address to the application layer to check
  
  val a2l_link_addr_in          = Wire(UInt((addrWidth+1).W))
  val a2l_link_addr             = withClockAndReset(link.clk.asClock, link.reset.asAsyncReset){RegNext(a2l_link_addr_in, 0.U)}
  a2l_link_addr_in              := Mux(link.ack_update, link.ack_addr, a2l_link_addr)
  
  val link_addr_to_app_clk  = Module(new WlinkGenericFCReplayAddrSync(addrWidth+1))
  link_addr_to_app_clk.w.clk    := link.clk.asClock
  link_addr_to_app_clk.w.reset  := link.reset
  link_addr_to_app_clk.w.inc    := a2l_link_addr =/= a2l_link_addr_in //new
  link_addr_to_app_clk.w.addr   := a2l_link_addr_in
  
  link_addr_to_app_clk.r.clk    := app.clk.asClock
  link_addr_to_app_clk.r.reset  := app.reset
  a2l_link_addr_app_clk         := link_addr_to_app_clk.r.addr
  
  //--------------------------------
  // Assertions
  
//   withClockAndReset(link.clk.asClock, link.reset.asAsyncReset){
//     assert(~(link.advance & ~link.valid), "link.advance asserted when link.valid is low!")
//   }
  
}


// object WavReplayFIFOGen extends App {  
//   implicit val p: Parameters = new BaseXbarConfig
//   
//   val axiverilog = (new ChiselStage).emitVerilog(
//     new WlinkGenericFCReplayV2(16, 4, "blah")(p),
//      
//     //args
//     Array("--target-dir", "output/")
//   )
// }

/**
  *   Handles passing the address in each direction. Essentially always reads/writes
  *   and only updates the output when r.ready is set in the multibitsync
  */
class WlinkGenericFCReplayAddrSync(dataWidth: Int)(implicit p: Parameters) extends RawModule{
  val w = IO(new Bundle{
    val clk     = Input (Clock())
    val reset   = Input (Reset())
    val inc     = Input (Bool())
    val addr    = Input (UInt(dataWidth.W))
  })
  
  val r = IO(new Bundle{
    val clk     = Input (Clock())
    val reset   = Input (Reset())
    val addr    = Output(UInt(dataWidth.W))
  })
  
  val raddr_in  = Wire(UInt(dataWidth.W))
  val raddr     = withClockAndReset(r.clk, r.reset.asAsyncReset){RegNext(raddr_in, 0.U)}
  
  
  val addrsync      = Module(new WavMultibitSync(dataWidth))
  addrsync.w.clk    := w.clk
  addrsync.w.reset  := w.reset
  addrsync.w.inc    := w.inc
  addrsync.w.data   := w.addr
  
  addrsync.r.clk    := r.clk
  addrsync.r.reset  := r.reset
  addrsync.r.inc    := addrsync.r.ready//true.B
  
  raddr_in          := Mux(addrsync.r.ready, addrsync.r.data, raddr)
  
  r.addr            := raddr
  
}

