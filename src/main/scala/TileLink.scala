package wav.wlink

import wav.common._

import chisel3._
import chisel3.util._

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

import scala.math.max


//Level of the TL Port
object WlinkTLLevel extends scala.Enumeration {
  type WlinkTLLevel = Value
  val TLUL, TLUH, TLC = Value
}
import WlinkTLLevel._

/**
  *   TileLink Quick Reference
  *   TL-UL
  *     - Only A/D channel
  *       - Get / PutFull / PutPartial
  *
  *   TL-UH
  *     - Still A/D Channels
  *       - Arithmetic / Logical / Intent / HintAck
  *
  *   TL-C
  *     - Adds BCE Channels
  */



case object WlinkTLBus   extends Field[Option[Seq[WlinkTLParams]]](None)



//Use this when we want to describe a slave port
case class WlinkTLSlavePortParams(
  base          : BigInt,
  size          : BigInt,
  name          : String,
  beatBytes     : Int,
  idBits        : Int,
  level         : WlinkTLLevel = WlinkTLLevel.TLUL,
  maxXferBytes  : Int = 4096,
  executable    : Boolean = true
)

case class WlinkTLMasterPortParams(
  base          : BigInt,
  size          : BigInt,                 //Used to force address size on Master Port
  beatBytes     : Int,
  idBits        : Int,
  name          : String,
  maxXferBytes  : Int = 4096,
  level         : WlinkTLLevel = WlinkTLLevel.TLUL     //Used to force Channel creation on Master Port
)

case class WlinkTLParams(
  master              : Option[WlinkTLMasterPortParams] = None,
  slave               : Option[WlinkTLSlavePortParams]  = None,
  aChannelFifoSize    : Int = 32,
  bChannelFifoSize    : Int = 8,
  cChannelFifoSize    : Int = 8,
  dChannelFifoSize    : Int = 32,
  eChannelFifoSize    : Int = 8,
  startingLongDataId  : Int = 0x80,
  startingShortDataId : Int = 0x8,
  noRegTest           : Boolean = false)



object WlinkTLCreatePorts{

  def apply(nodes: scala.collection.mutable.Buffer[(TLToWlink, WlinkTLParams, Boolean)])(implicit p: Parameters): Unit = {

    nodes.foreach{ case (node, params, connected) =>
      
      if(!connected){
        //For now just assuming they are the same
        val myTLSlvDriver = params.slave match {
          case Some(spp) => {
            val device = new SimpleBus(spp.name, Nil)
            TLManagerNode(Seq(TLSlavePortParameters.v1(
              managers = Seq(TLSlaveParameters.v1(
                address            = AddressSet.misaligned(spp.base, spp.size),
                resources          = device.ranges,
                executable         = spp.executable,
                supportsGet        = TransferSizes(1, spp.maxXferBytes),
                supportsPutFull    = TransferSizes(1, spp.maxXferBytes),
                supportsPutPartial = TransferSizes(1, spp.maxXferBytes),
                supportsArithmetic = if (spp.level == TLUH || spp.level == TLC) TransferSizes(1, spp.maxXferBytes) else TransferSizes.none,
                supportsLogical    = if (spp.level == TLUH || spp.level == TLC) TransferSizes(1, spp.maxXferBytes) else TransferSizes.none,
                supportsHint       = if (spp.level == TLUH || spp.level == TLC) TransferSizes(1, spp.maxXferBytes) else TransferSizes.none,
                supportsAcquireT   = if (                     spp.level == TLC) TransferSizes(1, spp.maxXferBytes) else TransferSizes.none,
                supportsAcquireB   = if (                     spp.level == TLC) TransferSizes(1, spp.maxXferBytes) else TransferSizes.none,
                )),
              beatBytes = spp.beatBytes)))
            }
            case None => None
        }
        val myTLMstDriver = params.master match {
          case Some(mpp) => {
            TLClientNode(Seq(TLMasterPortParameters.v2(Seq(TLMasterParameters.v2(
              name = mpp.name,
              emits = TLMasterToSlaveTransferSizes(
                get         = TransferSizes(1, mpp.maxXferBytes),
                putFull     = TransferSizes(1, mpp.maxXferBytes),
                putPartial  = TransferSizes(1, mpp.maxXferBytes),
                arithmetic  = if (mpp.level == TLUH || mpp.level == TLC) TransferSizes(1, mpp.maxXferBytes) else TransferSizes.none,
                logical     = if (mpp.level == TLUH || mpp.level == TLC) TransferSizes(1, mpp.maxXferBytes) else TransferSizes.none,
                hint        = if (mpp.level == TLUH || mpp.level == TLC) TransferSizes(1, mpp.maxXferBytes) else TransferSizes.none,
                acquireT    = if (                     mpp.level == TLC) TransferSizes(1, mpp.maxXferBytes) else TransferSizes.none,
                acquireB    = if (                     mpp.level == TLC) TransferSizes(1, mpp.maxXferBytes) else TransferSizes.none
                ),
              sourceId = IdRange(0, 1 << mpp.idBits))))))
          }
          case None => None
        }
        
        myTLSlvDriver match{
          case slvDrv: TLManagerNode => {
            println(s"see myTLSlvDriver: ${slvDrv}")
            node.tlMasterNode match {
              case tlmn: TLClientNode => slvDrv     := tlmn
              case None => 
            } 
            val tlMst = InModuleBody { slvDrv.makeIOs() }
          }
          case _         => println("I didn't see a myTLSlvDriver!!")
        }
        myTLMstDriver match{
          case mstDrv: TLClientNode => {
            println(s"see myTLMstDriver: ${mstDrv}")
            node.tlSlaveNode match {
              case tlsn: TLManagerNode => tlsn  := mstDrv
              case None => 
            } 
            
            val tlSlv = InModuleBody { mstDrv.makeIOs() }
          }
          case _         => println("I didn't see a myTLMstDriver!!")
        }
        
        
        
        
      }
    }
  }
}


trait CanHaveTLPort { this: Wlink =>
  
  private val tlParamsOpt = params.tlParams
  private var index        = 0x0
  
  val tl2wlNodes = scala.collection.mutable.Buffer[(TLToWlink, WlinkTLParams, Boolean)]()
  
  
  tlParamsOpt.map(paramsmap =>
    paramsmap.foreach{tlParams =>
      val tl2wl  = LazyModule(new TLToWlink(params           = tlParams,
                                            baseAddr         = wlinkBaseAddr + params.tlFCOffset + index))
        
      //Id Checks (need to make this better)
      //NEED TO ADD ID CHECKS
                                                     
      
      val temp = (tl2wl, tlParams, false)//, iniIdNode, tgtIdNode)
      tl2wlNodes += temp
      
                                                     
      txrouter.node         := tl2wl.aFC.txnode
      //txrouter.node         := tl2wl.bFC.txnode
      //txrouter.node         := tl2wl.cFC.txnode
      txrouter.node         := tl2wl.dFC.txnode
      //txrouter.node         := tl2wl.eFC.txnode

      tl2wl.aFC.rxnode    := rxrouter.node
      //tl2wl.bFC.rxnode    := rxrouter.node
      //tl2wl.cFC.rxnode    := rxrouter.node
      tl2wl.dFC.rxnode    := rxrouter.node
      //tl2wl.eFC.rxnode    := rxrouter.node

      tl2wl.xbar.node     := xbar.node

      
      // Connect the clocks/resets/enables internally
      val connectingClocksEnables = InModuleBody { 
        tl2wl.module.io.app.clk              := app_clk_scan
        tl2wl.module.io.app.reset            := app_clk_reset_scan
        tl2wl.module.io.app.enable           := swi_enable

        tl2wl.module.io.tx.clk               := tx_link_clk
        tl2wl.module.io.tx.reset             := tx_link_clk_reset

        tl2wl.module.io.rx.clk               := rx_link_clk
        tl2wl.module.io.rx.reset             := rx_link_clk_reset
        
        crc_errors(crcErrIndex)              := tl2wl.module.io.rx.crc_err
        crcErrIndex                          += 1
      }
      
      index = index + 0x800
      
    }
  )
  
  //When you don't want to have another node to connect at a higer level such
  //as only generating the wlink itself
  if(p(WlinkOnlyGen)){
    WlinkTLCreatePorts(tl2wlNodes)
  }
  
}

/**
  *   Main TL to Wlink conversion node
  *
  */

class TLToWlink(val params: WlinkTLParams, val baseAddr: BigInt = 0x0)(implicit p: Parameters) extends LazyModule{
  
  val xbar    = LazyModule(new APBFanout)
  
  /*
      A/B/C Channel
      --------------------
      opcode  - 3
      param   - 3  
      size    - log2Up(log2Ceil(maxTransfer))
      source  - idWidth
      address - addressWidth
      mask    - beatBytes/8
      data    - beatBytes*8
      corrupt - 1
      
      D Channel
      --------------------
      opcode  - 3
      param   - 3  
      size    - log2Up(log2Ceil(maxTransfer))
      source  - idWidth
      sink    - idWidth   ???Check
      denied  - 1
      data    - beatBytes*8
      corrupt - 1
      
      E Channel
      --------------------
      sink    - idWidth
      
  */
  
  var needBCE           = false
  var chPrefix          = "tl"
  
  //Manager == Slave
  var tlSlaveIdWidth    = 0
  var tlSlaveDataWidth  = 0
  var tlSlaveAddrWidth  = 0
  var tlSlaveSizeWidth  = 0
  var aChA2LWidth       = 8
  var bChA2LWidth       = 8
  var cChA2LWidth       = 8
  var dChA2LWidth       = 8
  var eChA2LWidth       = 8
  
  val tlSlaveNode = params.slave match {
    case Some(spp)  => {
      val device = new SimpleBus(spp.name, Nil)
      
      chPrefix          = chPrefix + "_" + spp.name + "slv_"
      
      tlSlaveIdWidth    = spp.idBits
      tlSlaveDataWidth  = spp.beatBytes
      tlSlaveAddrWidth  = log2Ceil(spp.base + spp.size - 1)
      tlSlaveSizeWidth  = log2Ceil(spp.maxXferBytes)
      println(s"tlSlaveAddrWidth: ${tlSlaveAddrWidth}")
      
      aChA2LWidth       = (3 + 
                           3 + 
                           tlSlaveSizeWidth + 
                           spp.idBits + 
                           tlSlaveAddrWidth + 
                           spp.beatBytes + 
                           spp.beatBytes*8 +
                           1)
      
      dChA2LWidth       = (3 + 
                           3 + 
                           tlSlaveSizeWidth + 
                           spp.idBits + 
                           spp.idBits + 
                           1 + 
                           spp.beatBytes*8 +
                           1)
      
      if(spp.level == TLC){
        needBCE         = true
        bChA2LWidth     = aChA2LWidth
        cChA2LWidth     = aChA2LWidth
        eChA2LWidth     = spp.idBits
      }
      
      TLManagerNode(Seq(TLSlavePortParameters.v1(
        managers = Seq(TLSlaveParameters.v1(
          address            = AddressSet.misaligned(spp.base, spp.size),
          resources          = device.ranges,
          executable         = spp.executable,
          supportsGet        = TransferSizes(1, spp.maxXferBytes),
          supportsPutFull    = TransferSizes(1, spp.maxXferBytes),
          supportsPutPartial = TransferSizes(1, spp.maxXferBytes),
          supportsArithmetic = if (spp.level == TLUH || spp.level == TLC) TransferSizes(1, spp.maxXferBytes) else TransferSizes.none,
          supportsLogical    = if (spp.level == TLUH || spp.level == TLC) TransferSizes(1, spp.maxXferBytes) else TransferSizes.none,
          supportsHint       = if (spp.level == TLUH || spp.level == TLC) TransferSizes(1, spp.maxXferBytes) else TransferSizes.none,
          supportsAcquireT   = if (                     spp.level == TLC) TransferSizes(1, spp.maxXferBytes) else TransferSizes.none,
          supportsAcquireB   = if (                     spp.level == TLC) TransferSizes(1, spp.maxXferBytes) else TransferSizes.none,
          )),
        beatBytes = spp.beatBytes)))
    }
    case None       => None
  }
  
  //Client == Master
  var tlMasterIdWidth   = 0
  var tlMasterDataWidth = 0
  var tlMasterAddrWidth = 0
  var tlMasterSizeWidth = 0
  var aChL2AWidth       = 8
  var bChL2AWidth       = 8
  var cChL2AWidth       = 8
  var dChL2AWidth       = 8
  var eChL2AWidth       = 8
  
  val tlMasterNode = params.master match{
    case Some(mpp)  => {
      chPrefix            = chPrefix + "_" + mpp.name + "mst_"
      
      tlMasterIdWidth     = mpp.idBits
      tlMasterDataWidth   = mpp.beatBytes
      tlMasterAddrWidth   = log2Ceil(mpp.base + mpp.size - 1)
      tlMasterSizeWidth   = log2Ceil(mpp.maxXferBytes)
      println(s"tlMasterAddrWidth: ${tlMasterAddrWidth}")
      
      aChL2AWidth       = (3 + 
                           3 + 
                           tlMasterSizeWidth + 
                           mpp.idBits + 
                           tlSlaveAddrWidth + 
                           mpp.beatBytes + 
                           mpp.beatBytes*8 +
                           1)
      
      dChL2AWidth       = (3 + 
                           3 + 
                           tlMasterSizeWidth + 
                           mpp.idBits + 
                           mpp.idBits + 
                           1 + 
                           mpp.beatBytes*8 +
                           1)
      
      if(mpp.level == TLC){
        needBCE         = true
        bChL2AWidth     = aChL2AWidth
        cChL2AWidth     = aChL2AWidth
        eChL2AWidth     = mpp.idBits
      }
      
      TLClientNode(Seq(TLMasterPortParameters.v2(Seq(TLMasterParameters.v2(
        name = mpp.name,
        emits = TLMasterToSlaveTransferSizes(
          get         = TransferSizes(1, mpp.maxXferBytes),
          putFull     = TransferSizes(1, mpp.maxXferBytes),
          putPartial  = TransferSizes(1, mpp.maxXferBytes),
          arithmetic  = if (mpp.level == TLUH || mpp.level == TLC) TransferSizes(1, mpp.maxXferBytes) else TransferSizes.none,
          logical     = if (mpp.level == TLUH || mpp.level == TLC) TransferSizes(1, mpp.maxXferBytes) else TransferSizes.none,
          hint        = if (mpp.level == TLUH || mpp.level == TLC) TransferSizes(1, mpp.maxXferBytes) else TransferSizes.none,
          acquireT    = if (                     mpp.level == TLC) TransferSizes(1, mpp.maxXferBytes) else TransferSizes.none,
          acquireB    = if (                     mpp.level == TLC) TransferSizes(1, mpp.maxXferBytes) else TransferSizes.none
          ),
        sourceId = IdRange(0, 1 << mpp.idBits))))))
    }
    case None       => None
  }
  
//   val tlMasterWidthWidgetNode = params.master match {
//     case Some(mpp)  => {
//       //require(params.master.get.beatBytes == Some(Int), "forceMasterBeatBytes is required when TLMasterPortParameters are defined in TLToWlink")
//       TLWidthWidget(params.master.get.beatBytes)
//     }
//     case None       => None
//   }
  
  
  
  
  val aFC = LazyModule(new WlinkGenericFCSM(baseAddr        = baseAddr + 0x0, 
                                            a2lDataWidth    = aChA2LWidth, 
                                            a2lDepth        = params.aChannelFifoSize, 
                                            l2aDataWidth    = aChL2AWidth, 
                                            l2aDepth        = params.aChannelFifoSize, 
                                            channelName     = chPrefix + "aFC", 
                                            dataIdDefault   = params.startingLongDataId,
                                            crIdDefault     = params.startingShortDataId,
                                            crackIdDefault  = params.startingShortDataId + 1,
                                            ackIdDefault    = params.startingShortDataId + 2,
                                            nackIdDefault   = params.startingShortDataId + 3,
                                            noRegTest       = params.noRegTest))
  
  
  val dFC = LazyModule(new WlinkGenericFCSM(baseAddr        = baseAddr + 0x100, 
                                            a2lDataWidth    = dChA2LWidth, 
                                            a2lDepth        = params.dChannelFifoSize, 
                                            l2aDataWidth    = dChL2AWidth, 
                                            l2aDepth        = params.dChannelFifoSize, 
                                            channelName     = chPrefix + "dFC", 
                                            dataIdDefault   = params.startingLongDataId  + 1,
                                            crIdDefault     = params.startingShortDataId + 4,
                                            crackIdDefault  = params.startingShortDataId + 5,
                                            ackIdDefault    = params.startingShortDataId + 6,
                                            nackIdDefault   = params.startingShortDataId + 7,
                                            noRegTest       = params.noRegTest))
  
  aFC.suggestName(chPrefix + "aFC")
  dFC.suggestName(chPrefix + "dFC")
  aFC.node   := xbar.node
  dFC.node   := xbar.node
  
  if(needBCE){
    val bFC = LazyModule(new WlinkGenericFCSM(baseAddr        = baseAddr + 0x200, 
                                              a2lDataWidth    = bChA2LWidth, 
                                              a2lDepth        = params.bChannelFifoSize, 
                                              l2aDataWidth    = bChL2AWidth, 
                                              l2aDepth        = params.bChannelFifoSize, 
                                              channelName     = chPrefix + "bFC", 
                                              dataIdDefault   = params.startingLongDataId  + 2,
                                              crIdDefault     = params.startingShortDataId + 8,
                                              crackIdDefault  = params.startingShortDataId + 9,
                                              ackIdDefault    = params.startingShortDataId + 10,
                                              nackIdDefault   = params.startingShortDataId + 11,
                                              noRegTest       = params.noRegTest))

    val cFC = LazyModule(new WlinkGenericFCSM(baseAddr        = baseAddr + 0x300, 
                                              a2lDataWidth    = cChA2LWidth, 
                                              a2lDepth        = params.cChannelFifoSize, 
                                              l2aDataWidth    = cChL2AWidth, 
                                              l2aDepth        = params.cChannelFifoSize, 
                                              channelName     = chPrefix + "cFC", 
                                              dataIdDefault   = params.startingLongDataId  + 3,
                                              crIdDefault     = params.startingShortDataId + 12,
                                              crackIdDefault  = params.startingShortDataId + 13,
                                              ackIdDefault    = params.startingShortDataId + 14,
                                              nackIdDefault   = params.startingShortDataId + 15,
                                              noRegTest       = params.noRegTest))

    val eFC = LazyModule(new WlinkGenericFCSM(baseAddr        = baseAddr + 0x400, 
                                              a2lDataWidth    = eChA2LWidth, 
                                              a2lDepth        = params.eChannelFifoSize, 
                                              l2aDataWidth    = eChL2AWidth, 
                                              l2aDepth        = params.eChannelFifoSize, 
                                              channelName     = chPrefix + "eFC", 
                                              dataIdDefault   = params.startingLongDataId  + 4,
                                              crIdDefault     = params.startingShortDataId + 16,
                                              crackIdDefault  = params.startingShortDataId + 17,
                                              ackIdDefault    = params.startingShortDataId + 18,
                                              nackIdDefault   = params.startingShortDataId + 19,
                                              noRegTest       = params.noRegTest))
    
    bFC.suggestName(chPrefix + "bFC")
    cFC.suggestName(chPrefix + "cFC")
    eFC.suggestName(chPrefix + "eFC")
    
    bFC.node   := xbar.node
    cFC.node   := xbar.node
    eFC.node   := xbar.node
  }
  
  
  lazy val module = new LazyModuleImp(this) with RequireAsyncReset {
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
    
    if(needBCE) {
//       io.rx.crc_err :=  (aFC.module.io.rx.crc_err | 
//                          bFC.module.io.rx.crc_err |
//                          cFC.module.io.rx.crc_err |
//                          dFC.module.io.rx.crc_err |
//                          eFC.module.io.rx.crc_err)
    } else {
      io.rx.crc_err := (aFC.module.io.rx.crc_err | dFC.module.io.rx.crc_err)
    }
    
    //====================================
    // Packet Format
    //====================================
    // corrupt |          opcode | param | size | source | mask | data | address    (A/B/C)
    // corrupt | denied | opcode | param | size | source | sink | data              (D)
    // sink                                                                         (E)
    //
    
    
    // Clocking / Enables
    aFC.module.io.tx.clk        := io.tx.clk
    aFC.module.io.tx.reset      := io.tx.reset
    aFC.module.io.rx.clk        := io.rx.clk
    aFC.module.io.rx.reset      := io.rx.reset
    
    aFC.module.io.app.clk       := io.app.clk
    aFC.module.io.app.reset     := io.app.reset
    aFC.module.io.app.enable    := io.app.enable
    
    aFC.module.io.app.a2l_data  := 0.U
    aFC.module.io.app.a2l_valid := false.B
    aFC.module.io.app.l2a_accept:= false.B
    
    dFC.module.io.tx.clk        := io.tx.clk
    dFC.module.io.tx.reset      := io.tx.reset
    dFC.module.io.rx.clk        := io.rx.clk
    dFC.module.io.rx.reset      := io.rx.reset
    
    dFC.module.io.app.clk       := io.app.clk
    dFC.module.io.app.reset     := io.app.reset
    dFC.module.io.app.enable    := io.app.enable
    
    dFC.module.io.app.a2l_data  := 0.U
    dFC.module.io.app.a2l_valid := false.B
    dFC.module.io.app.l2a_accept:= false.B
    
    if(needBCE){
//       bFC.module.io.tx.clk        := io.tx.clk
//       bFC.module.io.tx.reset      := io.tx.reset
//       bFC.module.io.rx.clk        := io.rx.clk
//       bFC.module.io.rx.reset      := io.rx.reset
// 
//       bFC.module.io.app.clk       := io.app.clk
//       bFC.module.io.app.reset     := io.app.reset
//       bFC.module.io.app.enable    := io.app.enable
//       
//       bFC.module.io.app.a2l_data  := 0.U
//       bFC.module.io.app.a2l_valid := false.B
//       bFC.module.io.app.l2a_accept:= false.B
//       
//       cFC.module.io.tx.clk        := io.tx.clk
//       cFC.module.io.tx.reset      := io.tx.reset
//       cFC.module.io.rx.clk        := io.rx.clk
//       cFC.module.io.rx.reset      := io.rx.reset
// 
//       cFC.module.io.app.clk       := io.app.clk
//       cFC.module.io.app.reset     := io.app.reset
//       cFC.module.io.app.enable    := io.app.enable
//       
//       cFC.module.io.app.a2l_data  := 0.U
//       cFC.module.io.app.a2l_valid := false.B
//       cFC.module.io.app.l2a_accept:= false.B
//       
//       eFC.module.io.tx.clk        := io.tx.clk
//       eFC.module.io.tx.reset      := io.tx.reset
//       eFC.module.io.rx.clk        := io.rx.clk
//       eFC.module.io.rx.reset      := io.rx.reset
// 
//       eFC.module.io.app.clk       := io.app.clk
//       eFC.module.io.app.reset     := io.app.reset
//       eFC.module.io.app.enable    := io.app.enable
//       
//       eFC.module.io.app.a2l_data  := 0.U
//       eFC.module.io.app.a2l_valid := false.B
//       eFC.module.io.app.l2a_accept:= false.B
    }
    
    
    
    //====================================
    // TL-Slave
    //====================================
    
    tlSlaveNode match {
      case slaveNode: TLManagerNode => {

        val tlSlv = slaveNode.in.head._1

        aFC.module.io.app.a2l_data := Cat(tlSlv.a.bits.corrupt,
                                          tlSlv.a.bits.opcode,
                                          tlSlv.a.bits.param,
                                          tlSlv.a.bits.size,
                                          tlSlv.a.bits.source,
                                          tlSlv.a.bits.mask,
                                          tlSlv.a.bits.data,
                                          tlSlv.a.bits.address)
        aFC.module.io.app.a2l_valid := tlSlv.a.valid
        tlSlv.a.ready               := aFC.module.io.app.a2l_ready


        var index = 0
        tlSlv.d.bits.data           := aFC.module.io.app.l2a_data((tlSlaveDataWidth*8)-1, 0)
        index                       = tlSlaveDataWidth*8

        tlSlv.d.bits.sink           := aFC.module.io.app.l2a_data(index + tlSlaveIdWidth - 1, index)
        index                       = index + tlSlaveIdWidth

        tlSlv.d.bits.source         := aFC.module.io.app.l2a_data(index + tlSlaveIdWidth - 1, index)
        index                       = index + tlSlaveIdWidth

        tlSlv.d.bits.size           := aFC.module.io.app.l2a_data(index + tlSlaveSizeWidth - 1, index)
        index                       = index + tlSlaveSizeWidth

        tlSlv.d.bits.param          := aFC.module.io.app.l2a_data(index + 3 - 1, index)
        index                       = index + 3

        tlSlv.d.bits.opcode         := aFC.module.io.app.l2a_data(index + 3 - 1, index)
        index                       = index + 3

        tlSlv.d.bits.denied         := aFC.module.io.app.l2a_data(index)
        index                       = index + 1

        tlSlv.d.bits.corrupt        := aFC.module.io.app.l2a_data(index)


        dFC.module.io.app.l2a_accept:= tlSlv.d.ready
        tlSlv.d.valid               := dFC.module.io.app.l2a_valid
      }
      case None => 
    }
    
    //====================================
    // TL-Master
    //====================================
    tlMasterNode match {
      case masterNode: TLClientNode => {
        
        println(s"tlMasterAddrWidth: ${tlMasterAddrWidth}")
        println(s"tlMasterDataWidth: ${tlMasterDataWidth}")
        println(s"tlMasterDataWidth*8: ${tlMasterDataWidth*8}")
        println(s"tlMasterIdWidth: ${tlMasterIdWidth}")
        println(s"tlMasterSizeWidth: ${tlMasterSizeWidth}")
        
        val tlMst = masterNode.out.head._1

        var index = 0
        tlMst.a.bits.address        := aFC.module.io.app.l2a_data(tlMasterAddrWidth-1, 0)
        index                       = tlMasterAddrWidth

        tlMst.a.bits.data           := aFC.module.io.app.l2a_data(index + (tlMasterDataWidth*8) - 1, index)
        index                       = index + (tlMasterDataWidth*8)

        tlMst.a.bits.mask           := aFC.module.io.app.l2a_data(index + tlMasterDataWidth - 1, index)
        index                       = index + tlMasterDataWidth

        tlMst.a.bits.source         := aFC.module.io.app.l2a_data(index + tlMasterIdWidth - 1, index)
        index                       = index + tlMasterIdWidth

        tlMst.a.bits.size           := aFC.module.io.app.l2a_data(index + tlMasterSizeWidth - 1, index)
        index                       = index + tlMasterSizeWidth

        tlMst.a.bits.param          := aFC.module.io.app.l2a_data(index + 3 - 1, index)
        index                       = index + 3

        tlMst.a.bits.opcode         := aFC.module.io.app.l2a_data(index + 3 - 1, index)
        index                       = index + 3

        tlMst.a.bits.corrupt        := aFC.module.io.app.l2a_data(index)


        aFC.module.io.app.l2a_accept:= tlMst.a.ready
        tlMst.a.valid               := aFC.module.io.app.l2a_valid




        dFC.module.io.app.a2l_data := Cat(tlMst.d.bits.corrupt,
                                          tlMst.d.bits.denied,
                                          tlMst.d.bits.opcode,
                                          tlMst.d.bits.param,
                                          tlMst.d.bits.size,
                                          tlMst.d.bits.source,
                                          tlMst.d.bits.sink,
                                          tlMst.d.bits.data)
        dFC.module.io.app.a2l_valid := tlMst.d.valid
        tlMst.d.ready               := dFC.module.io.app.a2l_ready
      }
      case None =>  
    }
    
  }
}
