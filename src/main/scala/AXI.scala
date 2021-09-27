package wav.wlink

import wav.common._

//import Chisel._
import chisel3._
import chisel3.util._
//import chisel3.experimental._
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

import scala.math.max

/**
  *   Parameter object which user will define in config fragments
  */
  
case object WlinkAxiBus   extends Field[Option[Seq[WlinkAxiParams]]](None)

case class WlinkAxiParams(
  base                : BigInt = 0x0,
  size                : BigInt = x"1_0000_0000_0000_0000",
  beatBytes           : Int = 32,
  idBits              : Int = 4,
  executable          : Boolean = true,
  name                : String = "axi",
  pipeLineStage       : Boolean = false,
  dataFifoSize        : Int = 32,
  nonDataFifoSize     : Int = 8,
  startingLongDataId  : Int = 0x80,
  startingShortDataId : Int = 0x8)


/**
  *   Object to create the AXI ports. 
  *   User should pass in the node list. Can use to create ports when you don't have a complete
  *   diplo graph but want to create the design
  */
object WlinkAxi4CreatePorts{
  def apply(nodes: scala.collection.mutable.Buffer[(AXI4ToWlink, WlinkAxiParams, Boolean, AXI4IdentityNode, AXI4IdentityNode)])(implicit p: Parameters): Unit = {
    nodes.foreach{ case (node, params, connected, iniIdNode, tgtIdNode) =>
      
      if(!connected){
        //dummy nodes, he's so smaht jenny
        val myAxiTgtDriver = node.axi_tgt_node.copy()
        val myAxiIniDriver = node.axi_ini_node.copy()
                
        myAxiTgtDriver    := iniIdNode
        tgtIdNode         := myAxiIniDriver

        val axi_ini = InModuleBody { myAxiTgtDriver.makeIOs()(valName=ValName(params.name + "_ini")) }
        val axi_tgt = InModuleBody { myAxiIniDriver.makeIOs()(valName=ValName(params.name + "_tgt")) }
      }
    }
  }
}


/**
  *   AXI trait to mixin
  *   This will check for any WlinkAxiParams and make the corresponding AXIToWlink Nodes
  *   and will connect each channel to the main routers
  */
trait CanHaveAXI4Port { this: Wlink =>
  
  //private val axiParamsOpt = p(WlinkAxiBus)
  private val axiParamsOpt = params.axiParams
  private var index        = 0x0
  
  //private var currentShortPacketIndex = 0x8
  //private var currentLongPacketIndex  = 0x80
  
  val axi2wlNodes = scala.collection.mutable.Buffer[(AXI4ToWlink, WlinkAxiParams, Boolean, AXI4IdentityNode, AXI4IdentityNode)]()
  
  axiParamsOpt.map(paramsmap =>
    paramsmap.foreach{axiParams =>
      val axiname = "wlink_" + axiParams.name
      val axi2wl  = LazyModule(new AXI4ToWlink(shortPacketStart = currentShortPacketIndex,
                                               longPacketStart  = currentLongPacketIndex,
                                               address          = AddressSet.misaligned(axiParams.base, axiParams.size), 
                                               beatBytes        = axiParams.beatBytes, 
                                               baseAddr         = wlinkBaseAddr + params.axiFCOffset + index,    
                                               idBits           = axiParams.idBits,
                                               name             = axiname,
                                               dataDepth        = axiParams.dataFifoSize,
                                               nonDataDepth     = axiParams.nonDataFifoSize,
                                               maxXferBytes     = axiParams.beatBytes * (1 << AXI4Parameters.lenBits),
                                               noRegTest        = params.noRegTest))
                                               
      //the addition of optional pipeline stage causes some grief as we want an "easy" (ok
      //as easy as we can get it) way to connect this without tons of pipeline checks all over
      //the place. So we will have an idtentity node here that we pass to the CreatePorts object.
      //There may be a better way to do this.......
      var tgtIdNode = AXI4IdentityNode()(valName=ValName(axiParams.name+"intermediate_tgt"))
      var iniIdNode = AXI4IdentityNode()(valName=ValName(axiParams.name+"intermediate_ini"))
      
      
      
      if(axiParams.pipeLineStage){
        // Optional pipeline stage. This can help when memories are used or for regioning the
        // AXI interface somehwere close to the boundary of the flooplan
        //Not using the companion object as we want to be able to name this
        val tgtBuffer = LazyModule(new AXI4Buffer(BufferParams(1, false, false), 
                                                  BufferParams(1, false, false), 
                                                  BufferParams(1, false, false), 
                                                  BufferParams(1, false, false), 
                                                  BufferParams(1, false, false)))
        val iniBuffer = LazyModule(new AXI4Buffer(BufferParams(1, false, false), 
                                                  BufferParams(1, false, false), 
                                                  BufferParams(1, false, false), 
                                                  BufferParams(1, false, false), 
                                                  BufferParams(1, false, false)))
      
        tgtBuffer.suggestName(axiParams.name+"_tgtBuffer")
        iniBuffer.suggestName(axiParams.name+"_iniBuffer")
        
        //Connect to the application clock and reset that we generate
        val connectPipeLine = InModuleBody{
          iniBuffer.module.clock := app_clk_scan.asClock
          iniBuffer.module.reset := app_clk_reset_scan.asAsyncReset
          
          tgtBuffer.module.clock := app_clk_scan.asClock
          tgtBuffer.module.reset := app_clk_reset_scan.asAsyncReset
        }
        
        axi2wl.axi_tgt_node := tgtBuffer.node := tgtIdNode
        iniIdNode           := iniBuffer.node := axi2wl.axi_ini_node
        
      } else {
      
        axi2wl.axi_tgt_node := tgtIdNode
        iniIdNode           := axi2wl.axi_ini_node
      
      }
      
      
      val temp = (axi2wl, axiParams, false, iniIdNode, tgtIdNode)
      axi2wlNodes += temp
      
                                                     
      txrouter.node         := axi2wl.awFC.txnode
      txrouter.node         := axi2wl.wFC.txnode
      txrouter.node         := axi2wl.bFC.txnode
      txrouter.node         := axi2wl.arFC.txnode
      txrouter.node         := axi2wl.rFC.txnode

      axi2wl.awFC.rxnode    := rxrouter.node
      axi2wl.wFC.rxnode     := rxrouter.node
      axi2wl.bFC.rxnode     := rxrouter.node
      axi2wl.arFC.rxnode    := rxrouter.node
      axi2wl.rFC.rxnode     := rxrouter.node

      axi2wl.xbar.node    := xbar.node

      
      // Connect the clocks/resets/enables internally
      val connectingClocksEnables = InModuleBody { 
        axi2wl.module.io.app.clk              := app_clk_scan
        axi2wl.module.io.app.reset            := app_clk_reset_scan
        axi2wl.module.io.app.enable           := swi_enable

        axi2wl.module.io.tx.clk               := tx_link_clk
        axi2wl.module.io.tx.reset             := tx_link_clk_reset

        axi2wl.module.io.rx.clk               := rx_link_clk
        axi2wl.module.io.rx.reset             := rx_link_clk_reset
        
        crc_errors(crcErrIndex)               := axi2wl.module.io.rx.crc_err
        crcErrIndex                           += 1
      }
      
      index = index + 0x800
      
      
      currentLongPacketIndex  += 5
      currentShortPacketIndex += 20
    }
  )
  
  //When you don't want to have another node to connect at a higer level such
  //as only generating the wlink itself
  if(p(WlinkOnlyGen)){
    WlinkAxi4CreatePorts(axi2wlNodes)
  }
}

/**
  *   Main AXI to Wlink conversion node
  *
  */
class AXI4ToWlink(
  shortPacketStart  : Int = 0x8,
  longPacketStart   : Int = 0x40,
  address           : Seq[AddressSet],
  addressSize       : Int = 64,             //Forces the address size internally
  idBits            : Int = 4,//8,
  cacheable         : Boolean = true,
  executable        : Boolean = true,
  beatBytes         : Int = 4,
  devName           : Option[String] = None,
  errors            : Seq[AddressSet] = Nil,
  wcorrupt          : Boolean = true,
  baseAddr          : BigInt = 0x0,
  name              : String = "",
  dataDepth         : Int = 32,
  nonDataDepth      : Int = 8,
  maxXferBytes      : Int = 4096,
  noRegTest         : Boolean = false
)(implicit p: Parameters) extends LazyModule{
  
  // This xbar is for register accesses
  //val xbar = LazyModule(new TLXbar)
  val xbar    = LazyModule(new APBFanout)
  
  val axi_tgt_node = AXI4SlaveNode(Seq(AXI4SlavePortParameters(
    Seq(AXI4SlaveParameters(
      address       = address,
      //resources     = resources,
      regionType    = if (cacheable) RegionType.UNCACHED else RegionType.IDEMPOTENT,
      executable    = executable,
      supportsRead  = TransferSizes(1, maxXferBytes), //we have no restrictions on the max size, but a user may request to limit it
      supportsWrite = TransferSizes(1, maxXferBytes),
      interleavedId = Some(0))),
    beatBytes  = beatBytes,
    requestKeys = if (wcorrupt) Seq(AMBACorrupt) else Seq(),
    minLatency = 1)))
  
  val axi_ini_node = AXI4MasterNode(
      Seq(AXI4MasterPortParameters(
        masters = Seq(AXI4MasterParameters(
          name = "axiIni",
          id   = IdRange(0, 1 << idBits/*8id bits*/))))))
  
  //Handles both Initiator/Target
  
  //val dataDepth = 32
  //val nonDataDepth = 8
  
  val chPrefix = if(name == "") "" else s"${name}_"
  
  
  // A[W|R] Channels are 25 + ID Length + Addr Size
  val aXwidth = addressSize + idBits + 25
  val bwidth  = idBits + 2
  val awFC = LazyModule(new WlinkGenericFCSM(baseAddr        = baseAddr + 0x0, 
                                             a2lDataWidth    = aXwidth, 
                                             a2lDepth        = nonDataDepth, 
                                             l2aDataWidth    = aXwidth, 
                                             l2aDepth        = nonDataDepth, 
                                             channelName     = chPrefix + "awFC", 
                                             dataIdDefault   = longPacketStart,
                                             crIdDefault     = shortPacketStart,
                                             crackIdDefault  = shortPacketStart + 1,
                                             ackIdDefault    = shortPacketStart + 2,
                                             nackIdDefault   = shortPacketStart + 3,
                                             noRegTest       = noRegTest))
      
  
  val wFC  = LazyModule(new WlinkGenericFCSM(baseAddr        = baseAddr + 0x100, 
                                             a2lDataWidth    = (beatBytes*8) + beatBytes + 1, 
                                             a2lDepth        = dataDepth, 
                                             l2aDataWidth    = (beatBytes*8) + beatBytes + 1, 
                                             l2aDepth        = dataDepth, 
                                             channelName     = chPrefix + "wFC", 
                                             dataIdDefault   = longPacketStart + 1,
                                             crIdDefault     = shortPacketStart + 4,
                                             crackIdDefault  = shortPacketStart + 5,
                                             ackIdDefault    = shortPacketStart + 6,
                                             nackIdDefault   = shortPacketStart + 7,
                                             noRegTest       = noRegTest))
  
  val bFC = LazyModule(new WlinkGenericFCSM(baseAddr        = baseAddr + 0x200, 
                                            a2lDataWidth    = bwidth, 
                                            a2lDepth        = nonDataDepth, 
                                            l2aDataWidth    = bwidth, 
                                            l2aDepth        = nonDataDepth, 
                                            channelName     = chPrefix + "bFC", 
                                            dataIdDefault   = longPacketStart + 2,
                                            crIdDefault     = shortPacketStart + 8,
                                            crackIdDefault  = shortPacketStart + 9,
                                            ackIdDefault    = shortPacketStart + 10,
                                            nackIdDefault   = shortPacketStart + 11,
                                            noRegTest       = noRegTest))
  
  val arFC = LazyModule(new WlinkGenericFCSM(baseAddr        = baseAddr + 0x300, 
                                             a2lDataWidth    = aXwidth, 
                                             a2lDepth        = nonDataDepth, 
                                             l2aDataWidth    = aXwidth, 
                                             l2aDepth        = nonDataDepth, 
                                             channelName     = chPrefix + "arFC", 
                                             dataIdDefault   = longPacketStart + 3,
                                             crIdDefault     = shortPacketStart + 12,
                                             crackIdDefault  = shortPacketStart + 13,
                                             ackIdDefault    = shortPacketStart + 14,
                                             nackIdDefault   = shortPacketStart + 15,
                                             noRegTest       = noRegTest))
  
  val rFC  = LazyModule(new WlinkGenericFCSM(baseAddr        = baseAddr + 0x400, 
                                             a2lDataWidth    = (beatBytes*8) + idBits + 2 + 1, 
                                             a2lDepth        = dataDepth, 
                                             l2aDataWidth    = (beatBytes*8) + idBits + 2 + 1, 
                                             l2aDepth        = dataDepth, 
                                             channelName     = chPrefix + "rFC", 
                                             dataIdDefault   = longPacketStart + 4,
                                             crIdDefault     = shortPacketStart + 16,
                                             crackIdDefault  = shortPacketStart + 17,
                                             ackIdDefault    = shortPacketStart + 18,
                                             nackIdDefault   = shortPacketStart + 19,
                                             noRegTest       = noRegTest))
  awFC.suggestName(name+"awFC")
  wFC.suggestName (name+"wFC")
  bFC.suggestName (name+"bFC")
  arFC.suggestName(name+"arFC")
  rFC.suggestName (name+"rFC")
  
  awFC.node   := xbar.node
  wFC.node    := xbar.node
  bFC.node    := xbar.node
  arFC.node   := xbar.node
  rFC.node    := xbar.node
  
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
    
    //require(node.in.size == 1, "AXI4ToWlink requires only one AXI port for node.in")
    
    io.rx.crc_err := (awFC.module.io.rx.crc_err | 
                      wFC.module.io.rx.crc_err  |
                      bFC.module.io.rx.crc_err  |
                      arFC.module.io.rx.crc_err |
                      rFC.module.io.rx.crc_err)
    
    //AXI Target Bundles
    val axi_tgt           = axi_tgt_node.in.head._1
    val axi_tgt_aw        = axi_tgt.aw
    val axi_tgt_w         = axi_tgt.w
    val axi_tgt_b         = axi_tgt.b
    val axi_tgt_ar        = axi_tgt.ar
    val axi_tgt_r         = axi_tgt.r
    
    
    val axi_ini           = axi_ini_node.out.head._1
    val axi_ini_aw        = axi_ini.aw
    val axi_ini_w         = axi_ini.w
    val axi_ini_b         = axi_ini.b
    val axi_ini_ar        = axi_ini.ar
    val axi_ini_r         = axi_ini.r
    
    
    //-----------------------------------------
    // AW Channel
    //-----------------------------------------
    
    awFC.module.io.tx.clk           := io.tx.clk
    awFC.module.io.tx.reset         := io.tx.reset
    awFC.module.io.rx.clk           := io.rx.clk
    awFC.module.io.rx.reset         := io.rx.reset
    
    awFC.module.io.app.clk          := io.app.clk
    awFC.module.io.app.reset        := io.app.reset
    awFC.module.io.app.enable       := io.app.enable
    awFC.module.io.app.a2l_valid    := axi_tgt_aw.valid
    axi_tgt_aw.ready                := awFC.module.io.app.a2l_ready
    
    //add a require for addr/ID lenth
    val aw_tgt_aw_addr     = WireDefault(UInt(addressSize.W), axi_tgt_aw.bits.addr)
    val aw_tgt_aw_id       = WireDefault(UInt(idBits.W), axi_tgt_aw.bits.id)
    
    awFC.module.io.app.a2l_data     := Cat(aw_tgt_aw_id,        
                                           aw_tgt_aw_addr,
                                           axi_tgt_aw.bits.qos,
                                           axi_tgt_aw.bits.prot,  
                                           axi_tgt_aw.bits.cache, 
                                           axi_tgt_aw.bits.lock,  
                                           axi_tgt_aw.bits.burst, 
                                           axi_tgt_aw.bits.size,  
                                           axi_tgt_aw.bits.len)     
    
    
    awFC.module.io.app.l2a_accept   := axi_ini_aw.ready
    axi_ini_aw.valid                := awFC.module.io.app.l2a_valid
    
    val aw_ini_aw_addr      = Wire(UInt(addressSize.W))
    val aw_ini_aw_id        = Wire(UInt(idBits.W))
        
    aw_ini_aw_id                    := awFC.module.io.app.l2a_data(25+addressSize+idBits-1,25+addressSize)
    aw_ini_aw_addr                  := awFC.module.io.app.l2a_data(25+addressSize-1,25)
    axi_ini_aw.bits.qos             := awFC.module.io.app.l2a_data(24,21)
    axi_ini_aw.bits.prot            := awFC.module.io.app.l2a_data(20,18)
    axi_ini_aw.bits.cache           := awFC.module.io.app.l2a_data(17,14)
    axi_ini_aw.bits.lock            := awFC.module.io.app.l2a_data(13)
    axi_ini_aw.bits.burst           := awFC.module.io.app.l2a_data(12,11)
    axi_ini_aw.bits.size            := awFC.module.io.app.l2a_data(10,8)
    axi_ini_aw.bits.len             := awFC.module.io.app.l2a_data(7,0)
    
    
    axi_ini_aw.bits.id              := aw_ini_aw_id
    axi_ini_aw.bits.addr            := aw_ini_aw_addr
    
    //-----------------------------------------
    // W Channel
    //-----------------------------------------
    
    wFC.module.io.tx.clk            := io.tx.clk
    wFC.module.io.tx.reset          := io.tx.reset
    wFC.module.io.rx.clk            := io.rx.clk
    wFC.module.io.rx.reset          := io.rx.reset
    
    wFC.module.io.app.clk           := io.app.clk
    wFC.module.io.app.reset         := io.app.reset
    wFC.module.io.app.enable        := io.app.enable
    wFC.module.io.app.a2l_valid     := axi_tgt_w.valid
    axi_tgt_w.ready                    := wFC.module.io.app.a2l_ready
    
    
    wFC.module.io.app.a2l_data      := Cat(axi_tgt_w.bits.last, axi_tgt_w.bits.strb, axi_tgt_w.bits.data)
    
    
    wFC.module.io.app.l2a_accept    := axi_ini_w.ready
    axi_ini_w.valid                 := wFC.module.io.app.l2a_valid                                                   
    
    axi_ini_w.bits.last             := wFC.module.io.app.l2a_data((beatBytes*8) + beatBytes)                     
    axi_ini_w.bits.strb             := wFC.module.io.app.l2a_data((beatBytes*8) + beatBytes - 1, beatBytes*8)  
    axi_ini_w.bits.data             := wFC.module.io.app.l2a_data((beatBytes*8)-1, 0)                                
    
    //-----------------------------------------
    // B Channel
    //-----------------------------------------
    
    bFC.module.io.tx.clk            := io.tx.clk
    bFC.module.io.tx.reset          := io.tx.reset
    bFC.module.io.rx.clk            := io.rx.clk
    bFC.module.io.rx.reset          := io.rx.reset
    
    bFC.module.io.app.clk           := io.app.clk
    bFC.module.io.app.reset         := io.app.reset
    bFC.module.io.app.enable        := io.app.enable
    bFC.module.io.app.a2l_valid     := axi_ini_b.valid
    axi_ini_b.ready                 := bFC.module.io.app.a2l_ready
    
    
    bFC.module.io.app.a2l_data      := Cat(axi_ini_b.bits.id, axi_ini_b.bits.resp)
    
    
    bFC.module.io.app.l2a_accept    := axi_tgt_b.ready
    axi_tgt_b.valid                 := bFC.module.io.app.l2a_valid
    
    axi_tgt_b.bits.resp             := bFC.module.io.app.l2a_data(1,0)
    axi_tgt_b.bits.id               := bFC.module.io.app.l2a_data(2+idBits-1,2)
    
    
    
    //-----------------------------------------
    // AR Channel
    //-----------------------------------------
    
    arFC.module.io.tx.clk           := io.tx.clk
    arFC.module.io.tx.reset         := io.tx.reset
    arFC.module.io.rx.clk           := io.rx.clk
    arFC.module.io.rx.reset         := io.rx.reset
    
    arFC.module.io.app.clk          := io.app.clk
    arFC.module.io.app.reset        := io.app.reset
    arFC.module.io.app.enable       := io.app.enable
    arFC.module.io.app.a2l_valid    := axi_tgt_ar.valid
    axi_tgt_ar.ready                := arFC.module.io.app.a2l_ready
    
    //add a require for addr/ID lenth
    val ar_tgt_ar_addr      = WireDefault(UInt(addressSize.W), axi_tgt_ar.bits.addr)
    val ar_tgt_ar_id        = WireDefault(UInt(idBits.W), axi_tgt_ar.bits.id)
    
    arFC.module.io.app.a2l_data     := Cat(ar_tgt_ar_id,        
                                           ar_tgt_ar_addr,
                                           axi_tgt_ar.bits.qos,
                                           axi_tgt_ar.bits.prot,  
                                           axi_tgt_ar.bits.cache, 
                                           axi_tgt_ar.bits.lock,  
                                           axi_tgt_ar.bits.burst, 
                                           axi_tgt_ar.bits.size,  
                                           axi_tgt_ar.bits.len,   
                                           )     
    
    
    arFC.module.io.app.l2a_accept   := axi_ini_ar.ready
    axi_ini_ar.valid                := arFC.module.io.app.l2a_valid
    
    val ar_ini_ar_addr      = Wire(UInt(addressSize.W))
    val ar_ini_ar_id        = Wire(UInt(idBits.W))
    
    
    ar_ini_ar_id                    := arFC.module.io.app.l2a_data(25+addressSize+idBits-1,25+addressSize)
    ar_ini_ar_addr                  := arFC.module.io.app.l2a_data(25+addressSize-1,25)
    axi_ini_ar.bits.qos             := arFC.module.io.app.l2a_data(24,21)
    axi_ini_ar.bits.prot            := arFC.module.io.app.l2a_data(20,18)
    axi_ini_ar.bits.cache           := arFC.module.io.app.l2a_data(17,14)
    axi_ini_ar.bits.lock            := arFC.module.io.app.l2a_data(13)
    axi_ini_ar.bits.burst           := arFC.module.io.app.l2a_data(12,11)
    axi_ini_ar.bits.size            := arFC.module.io.app.l2a_data(10,8)
    axi_ini_ar.bits.len             := arFC.module.io.app.l2a_data(7,0)
    
    
    axi_ini_ar.bits.id              := ar_ini_ar_id
    axi_ini_ar.bits.addr            := ar_ini_ar_addr
    
    
    //-----------------------------------------
    // R Channel
    //-----------------------------------------
    
    rFC.module.io.tx.clk            := io.tx.clk
    rFC.module.io.tx.reset          := io.tx.reset
    rFC.module.io.rx.clk            := io.rx.clk
    rFC.module.io.rx.reset          := io.rx.reset
    
    rFC.module.io.app.clk           := io.app.clk
    rFC.module.io.app.reset         := io.app.reset
    rFC.module.io.app.enable        := io.app.enable
    rFC.module.io.app.a2l_valid     := axi_ini_r.valid
    axi_ini_r.ready                 := rFC.module.io.app.a2l_ready
    
    
    rFC.module.io.app.a2l_data      := Cat(axi_ini_r.bits.resp, axi_ini_r.bits.last, axi_ini_r.bits.id, axi_ini_r.bits.data)
    
    
    rFC.module.io.app.l2a_accept    := axi_tgt_r.ready
    axi_tgt_r.valid                 := rFC.module.io.app.l2a_valid                                                   
    
    axi_tgt_r.bits.resp             := rFC.module.io.app.l2a_data((beatBytes*8)+idBits+2,(beatBytes*8)+idBits+1)   
    axi_tgt_r.bits.last             := rFC.module.io.app.l2a_data((beatBytes*8)+idBits)                     
    axi_tgt_r.bits.id               := rFC.module.io.app.l2a_data((beatBytes*8)+idBits-1, beatBytes*8)  
    axi_tgt_r.bits.data             := rFC.module.io.app.l2a_data((beatBytes*8)-1, 0) 
    
    
    
    dontTouch(axi_tgt)
    dontTouch(axi_ini)
    
  }
}
