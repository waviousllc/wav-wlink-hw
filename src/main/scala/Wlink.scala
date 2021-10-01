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



case object WlinkOnlyGen  extends Field[Boolean](false)
class WithWlinkOnlyGen extends Config ((site, here, up) => {
  case WlinkOnlyGen => true
})


case class WlinkParams(
  phyParams         : WlinkPHYBaseParams,
  txValidLaneSeq    : Seq[Boolean] = Seq.fill(256){true},
  rxValidLaneSeq    : Seq[Boolean] = Seq.fill(256){true},
  axiFCOffset       : BigInt = 0x200000,                          //Starting Offset from wlink base addr of all AXI conversion nodes
  axiParams         : Option[Seq[WlinkAxiParams]] = None,
  apbTgtParams      : Option[Seq[WlinkApbTgtParams]] = None,
  apbTgtFCOffset    : BigInt = 0x210000,                          //Starting Offset from wlink base addr of all APBTgt nodes
  apbIniParams      : Option[Seq[WlinkApbIniParams]] = None,
  apbIniFCOffset    : BigInt = 0x218000,                          //Starting Offset from wlink base addr of all APBIni nodes
  gbParams          : Option[Seq[WlinkGeneralBusParams]] = None,
  gbFCOffset        : BigInt = 0x220000,                          //Starting Base address of all GeneralBus nodes
  noRegTest         : Boolean= false
)


case object WlinkParamsKey extends Field[WlinkParams]

/**
  *   Top Level of the Wlink Controller
  *   
  */
class Wlink()(implicit p: Parameters) extends WlinkBase()
  with CanHaveAXI4Port
  with CanHaveAPBTgtPort
  with CanHaveAPBIniPort
  with CanHaveGeneralBusPort

class WlinkBase()(implicit p: Parameters) extends LazyModule with WlinkApplicationLayerChecks{

  val params        = p(WlinkParamsKey) 
  val wlinkBaseAddr = params.phyParams.baseAddr
  val numTxLanes    = params.phyParams.numTxLanes
  val numRxLanes    = params.phyParams.numRxLanes
  
  var crcErrIndex             = 0
    
  val device = new SimpleDevice("wavwlink", Seq("wavious,wlink"))
  val node = WavAPBRegisterNode(
    address = AddressSet.misaligned(wlinkBaseAddr+0x30000, 0x100),      ///FIX THIS 
    device  = device,
    beatBytes = 4,
    noRegTest = params.noRegTest)
  
  
  val xbar    = LazyModule(new APBFanout)
  
  //Magic, don't worry about it.
  val phy     = LazyModule(params.phyParams.phyType(p))
      
  val txrouter= LazyModule(new WlinkTxRouter)
  val txpstate= LazyModule(new WlinkTxPstateCtrl)
  
  val rxrouter= LazyModule(new WlinkRxRouter)
  val rxpstate= LazyModule(new WlinkRxPstateCtrl)
  
  val lltx    = LazyModule(new WlinkTxLinkLayer(numTxLanes, params.phyParams.phyDataWidth, validLaneSeq=params.txValidLaneSeq))
  val llrx    = LazyModule(new WlinkRxLinkLayer(numRxLanes, params.phyParams.phyDataWidth, validLaneSeq=params.rxValidLaneSeq))
  
  if(p(WlinkOnlyGen)){
    val ApbPort = APBMasterNode(
      portParams = Seq(APBMasterPortParameters(masters = Seq(APBMasterParameters(name = "ApbPort"))))
    )
    val apbport = InModuleBody {ApbPort.makeIOs()}
    xbar.node := ApbPort
  }
  
  //---------------------------------
  // Wlink Data Path Topology
  //---------------------------------
  txpstate.node       := txrouter.node
  lltx.node           := txpstate.node
  
  rxrouter.node       := llrx.node
  rxpstate.node       := rxrouter.node
  
  //---------------------------------
  // Register Topology
  //---------------------------------
  node                := xbar.node
  phy.node            := xbar.node
  
  
  // 
  lazy val tx_link_clk       = Wire(Bool())
  lazy val tx_link_clk_reset = Wire(Bool())
  lazy val rx_link_clk       = Wire(Bool())
  lazy val rx_link_clk_reset = Wire(Bool())
  lazy val swi_enable        = Wire(Bool())
  lazy val app_clk_scan      = Wire(Bool())
  lazy val app_clk_reset_scan= Wire(Bool())  
  
  lazy val crc_errors        = VecInit(Seq.fill(32)(false.B))
  
  lazy val module = new LazyModuleImp(this) with RequireAsyncReset{
    //---------------------------------------
    val scan          = IO(new WavScanBundle)
    val por_reset     = IO(Input (Bool()))
    val app_clk       = IO(Input (Bool()))
    val app_clk_reset = IO(Input (Bool()))
    val interrupt     = IO(Output(Bool()))

    val sb_reset_in   = IO(Input (Bool()))
    val sb_reset_out  = IO(Output(Bool()))
    val sb_wake       = IO(Output(Bool()))

    //---------------
    // PHY
    //---------------
    val user          = IO(phy.module.user.cloneType)
    val pad           = IO(phy.module.pad.cloneType)
    //---------------------------------------
    
    clock.suggestName("apb_clk")
    reset.suggestName("apb_reset")
    

    val swi_swreset                       = Wire(Bool())
    val active_tx_lanes                   = Wire(UInt(numTxLanes.W))
    val active_rx_lanes                   = Wire(UInt(numRxLanes.W))
    val swi_short_packet_max              = Wire(UInt(8.W))
    val swi_preq_data_id                  = Wire(UInt(8.W))
    val swi_sb_reset_in_muxed             = Wire(Bool())
    val swi_lltx_enable                   = Wire(Bool())
    val swi_llrx_enable                   = Wire(Bool())
    
    
    
    tx_link_clk                           := phy.module.link_tx.tx_link_clk
    tx_link_clk_reset                     := WavResetSync(tx_link_clk, (por_reset | swi_swreset), scan.asyncrst_ctrl)    
    rx_link_clk                           := phy.module.link_rx.rx_link_clk
    rx_link_clk_reset                     := WavResetSync(rx_link_clk, (por_reset | swi_swreset), scan.asyncrst_ctrl)   
    
    app_clk_scan                          := WavClockMux (scan.mode, scan.clk, app_clk)
    app_clk_reset_scan                    := WavResetSync(app_clk_scan, (app_clk_reset | swi_swreset), scan.asyncrst_ctrl)
    
    lltx.module.io.ll_tx_valid            := phy.module.link_tx.tx_ready
    phy.module.link_tx.tx_en              := txpstate.module.io.tx_en
    phy.module.link_tx.tx_link_data       := lltx.module.io.link_data
    phy.module.link_tx.tx_data_valid      := ~lltx.module.io.link_idle            //do we even need this?
    lltx.module.clock                     := tx_link_clk.asClock
    lltx.module.reset                     := tx_link_clk_reset.asAsyncReset    
    lltx.module.io.enable                 := swi_lltx_enable & ~swi_sb_reset_in_muxed
    
    lltx.module.io.active_lanes           := active_tx_lanes
    lltx.module.io.swi_short_packet_max   := swi_short_packet_max
    phy.module.link_tx.tx_active_lanes    := active_tx_lanes
    
    
    txrouter.module.clock                 := tx_link_clk.asClock
    txrouter.module.reset                 := tx_link_clk_reset.asAsyncReset
    txrouter.module.io.enable             := ~tx_link_clk_reset
    
    txpstate.module.clock                 := tx_link_clk.asClock
    txpstate.module.reset                 := tx_link_clk_reset.asAsyncReset
    txpstate.module.io.swi_preq_data_id   := swi_preq_data_id
    txpstate.module.io.tx_ready           := phy.module.link_tx.tx_ready
    sb_wake                               := txpstate.module.io.tx_en
    
    
    llrx.module.clock                     := rx_link_clk.asClock
    llrx.module.reset                     := rx_link_clk_reset.asAsyncReset
    llrx.module.io.enable                 := swi_llrx_enable
    llrx.module.io.active_lanes           := active_rx_lanes
    llrx.module.io.swi_short_packet_max   := swi_short_packet_max
    
    llrx.module.io.link_data              := phy.module.link_rx.rx_link_data
    llrx.module.io.ll_rx_valid            := phy.module.link_rx.rx_data_valid
    
    sb_reset_out                          := llrx.module.io.in_error_state
    
    phy.module.link_rx.rx_active_lanes    := active_rx_lanes
    
    rxpstate.module.clock                 := rx_link_clk.asClock
    rxpstate.module.reset                 := rx_link_clk_reset.asAsyncReset
    rxpstate.module.io.swi_preq_data_id   := swi_preq_data_id
    phy.module.link_rx.rx_enter_lp        := rxpstate.module.io.enter_lp
        
    //---------------------------------
    // PHY
    //---------------------------------
    phy.module.por_reset   := por_reset
    phy.module.scan.connectScan(scan)
    user <> phy.module.user
    pad  <> phy.module.pad 
    
    
    val crc_errors_w1c        = Wire(Bool())
    val crc_errors_int_en     = Wire(Bool())
    
    val ecc_corrupted_w1c     = Wire(Bool())
    val ecc_corrupted_int_en  = Wire(Bool())
    val ecc_corrected_w1c     = Wire(Bool())
    val ecc_corrected_int_en  = Wire(Bool())
    
    val ecc_corrected_sp = Module(new WavSyncPulse)
    ecc_corrected_sp.io.clk_in        := rx_link_clk.asClock
    ecc_corrected_sp.io.clk_in_reset  := rx_link_clk_reset.asAsyncReset
    ecc_corrected_sp.io.data_in       := llrx.module.io.ecc_corrected
    
    ecc_corrected_sp.io.clk_out       := clock
    ecc_corrected_sp.io.clk_out_reset := reset.asAsyncReset
    
    val ecc_corrupted_sp = Module(new WavSyncPulse)
    ecc_corrupted_sp.io.clk_in        := rx_link_clk.asClock
    ecc_corrupted_sp.io.clk_in_reset  := rx_link_clk_reset.asAsyncReset
    ecc_corrupted_sp.io.data_in       := llrx.module.io.ecc_corrupted
    
    ecc_corrupted_sp.io.clk_out       := clock
    ecc_corrupted_sp.io.clk_out_reset := reset.asAsyncReset
    
    
    
    interrupt         := ((crc_errors_int_en & crc_errors_w1c)       |
                          (ecc_corrupted_w1c & ecc_corrupted_int_en) |
                          (ecc_corrected_w1c & ecc_corrected_int_en))
    
                              
    //=======================================
    // Registers
    //=======================================
    node.regmap(
      WavSWReg(0x0,  "LinkCapabilities", "Link Capability Information",
        WavRO(numTxLanes.asUInt,                              "max_tx_lanes",     "Max number of lanes configured"),
        WavRO(numRxLanes.asUInt,                              "max_rx_lanes",     "Max number of lanes configured")),
      
      WavSWReg(0x4,  "PHYVersion", "",
        WavRO(params.phyParams.phyVersion,                    "phymode",          params.phyParams.phyVersionStr)),
      
      WavSWReg(0x8,  "EnableReset", "",
        WavRW(swi_enable,       true.B,                       "enable",           "Enable for application logic"),
        WavRW(swi_lltx_enable,  true.B,                       "lltx_enable",      "Enable for LL TX logic"),
        WavRW(swi_llrx_enable,  true.B,                       "lltx_enable",      "Enable for LL RX logic"),
        WavRW(swi_swreset,      false.B,                      "swreset",          "Software reset for application logic"),
        WavRW(swi_short_packet_max,       "h7f".U,            "short_packet_max", ""),
        WavRW(swi_preq_data_id,           "h2".U,             "preq_data_id",     "")),
        
      WavSWReg(0x10,  "ActiveTxLanes", "Lane Control",
        WavRW(active_tx_lanes,     (numTxLanes-1).asUInt,    "active_tx_lanes",   "Number of TX lanes active"),
        WavRW(active_rx_lanes,     (numRxLanes-1).asUInt,    "active_rx_lanes",   "Number of RX lanes active")),
      
      WavSWReg(0x30, "PstateCtrl", "Power State Control Settings",
        WavRW(txpstate.module.io.swi_delay_cycles,     1700.U,"delay_cycles",     "Number of link clk cycles after no data to enter LP State. A setting of 0 will disable LP entry"),
        WavRW(txpstate.module.io.swi_num_preq_send,    0.U,   "num_preq_send",    "Number of P Req packets to send"),
        WavRW(txpstate.module.io.swi_cycles_post_preq, 255.U, "cycles_post_preq", "Number of link clk cycles after sending P Req to enter LP state")),
      
      WavSWReg(0x34,  "LinkStatus", "LinkStatus",
        WavRWMux(in=sb_reset_in,                 muxed=swi_sb_reset_in_muxed,     reg_reset=false.B, mux_reset=false.B, "sb_reset_in", ""),
        WavRO(llrx.module.io.in_error_state,                  "in_error_state",   "Indicates if RX is in error state"),
        WavRO(phy.module.link_tx.tx_ready,                    "tx_active",        "Indicates if TX lanes are active, ready to transmit data"),
        WavRO(phy.module.link_rx.rx_data_valid,               "rx_active",        "Indicates if TX lanes are active, ready to transmit data (Always asserted when in GPIO Mode)")),
      
      WavSWReg(0x3c, "ErrInjection", "Do not modify without consulting design team",
        WavRW(lltx.module.io.swi_err_inj_data_id,      0.U,     "err_inj_data_id",  ""),
        WavRW(lltx.module.io.swi_err_inj_byte,         0.U,     "err_inj_byte",     ""),
        WavRW(lltx.module.io.swi_err_inj_bit,          0.U,     "err_inj_bit",      ""),
        WavRW(lltx.module.io.swi_err_inj,              false.B, "err_inj",          "")),
      
      WavSWReg(0x40,  "LinkInterrupts", "Interrupts for Link",
        WavW1C((crc_errors.orR),             crc_errors_w1c,               "crc_errors",           ""),
        WavRW(crc_errors_int_en,             true.B,                       "crc_errors_int_en",    ""),
        WavW1C(ecc_corrected_sp.io.data_out, ecc_corrected_w1c,            "ecc_corrected",        ""),
        WavRW(ecc_corrected_int_en,          false.B,                      "ecc_corrected_int_en", ""),
        WavW1C(ecc_corrupted_sp.io.data_out, ecc_corrupted_w1c,            "ecc_corrupted",        ""),
        WavRW(ecc_corrupted_int_en,          true.B,                       "ecc_corrupted_int_en", ""))
    )
  }
  
}

trait WlinkApplicationLayerChecks { this: WlinkBase =>
  
  // Id List for checking against already defined IDs
  // ID is key, string name of Node is value
  val idList = scala.collection.mutable.Map[Int, String]()
  
  def addId(id: Int, block: String): Unit = {
    require(!(idList contains id), s"Data ID ${id} is already used for ${idList(id)}!!")
    idList(id) = block
    println(s"${block}: 0x${id.toHexString}")
  }
  
}


/**********************************************************************
*   Generation Wrappers
**********************************************************************/


object WlinkGen extends App {  
    
  case class WlinkConfig(
    outputDir   : String = "./output",
    configName  : String = "wav.wlink.Wlink1LaneAXI32bitConfig")
  
  import scopt.OParser
  val builder = OParser.builder[WlinkConfig]
  val parser1 = {
    import builder._
    OParser.sequence(
      programName("WlinkGen"),
      head("WlinkGen", "0.1"),
      // option -f, --foo
      opt[String]('o', "outputdir")
        .action((x, c) => c.copy(outputDir = x))
        .text("output directory"),
      
      opt[String]('c', "configName")
        .action((x, c) => c.copy(configName = x))
        .text("config name <package>.<config>"),
    )
  }
  
  OParser.parse(parser1, args, WlinkConfig()) match {
    case Some(config) => {
      implicit val p: Parameters = WavConfigUtil.getConfig(Seq(config.configName))
  
      val verilog = (new ChiselStage).emitVerilog(
        LazyModule(new Wlink()(p)).module,

        //args
        Array("--target-dir", config.outputDir)
      )
    }
    case _ => {
      System.exit(1)
    }
  }
  
}

