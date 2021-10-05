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

import chisel3.util.random._


/*
.rst_start
WlinkSimpleTestHarness 
==========================
The ``WlinkSimpleTestHarness`` is a basic design wrapper which instantiates two Wlinks and drives some 
simple AXI traffic through the two Wlinks. There is a "LEFT" and "RIGHT" Wlink instance. LEFT/RIGHT in this
case are just names to help correspond to the diagram presented below.

.. figure :: wlink_test_harness.png
  :align:    center

There is a small state machine that will generate transactions. As showing in the diagram, the state machine
will perform a write on the AXI bus. This AXI write will propagate through the Wlink instances and access the
AXI4RAM connected to the LEFT instance. Responses follow the same path back.

The ``wlink_tb_top`` is the main top level of the testbench. It mainly instantiates the WlinkSimpleTestHarness
and exposes a few simple signals to know when the harness has completed the test.

Currently a user can generate a ``WlinkSimpleTestHarness`` and run a sim in the ``verif/`` directory.

.. todo ::

  Currently this TestHarness is only setup for a single AXI port on both Wlink instances and only checks
  that the data written is read (it doesn't check anything such as the source IDs). Future plans
  involve expanding this to auto-discover the application protocols and generate the respective test logic
  for each.
  
  You are free to use this, however do expect significant changes since this is still an area of active development
  
  This is also active development for using the TestHarness as part of a ``chiseltesters`` flow.


.. note ::

  Since the TestHarness is a generic test which tests certain portions of the design and due to the fact that it
  is generated with Chisel, it is possible that some portions of the design are optimized away by Chisels dead code
  elemination transform. 

.rst_end
*/
//Need to check on Chisel gitter why I need to put this on the outside of the LM
object THState extends ChiselEnum{
  val IDLE        = Value(0.U)
  val WRITE       = Value(1.U)
  val WRITE_RESP  = Value(2.U)
  val READ        = Value(3.U)
  val READ_RESP   = Value(4.U)
  val DONE        = Value(5.U)
}

class WlinkSimpleTestHarness()(implicit p: Parameters) extends LazyModule{
    
  
  val ApbPort = APBMasterNode(portParams = Seq(APBMasterPortParameters(masters = Seq(APBMasterParameters(name = "ApbPort")))))
  val apbXbar = LazyModule(new APBFanout)
  
  
  val wlink_left  = LazyModule(new Wlink()(p))
  
  // Need to change the baseAddr for the APB fanout and make sure that lanes are correct since they are mirrored in this case
  val pright      = p.alterPartial({
    case WlinkParamsKey => {p(WlinkParamsKey).copy(phyParams = p(WlinkParamsKey).phyParams.asInstanceOf[WlinkPHYGPIOExampleParams].copy(baseAddr = 0x10000000, 
                    numTxLanes=p(WlinkParamsKey).phyParams.numRxLanes,
		    numRxLanes=p(WlinkParamsKey).phyParams.numTxLanes)) }
  })
  val wlink_right = LazyModule(new Wlink()(pright))
  
  // Grab the node for configuring the test later
  val axiNode       = wlink_left.axi2wlNodes(0)._1
  
  val node = TLClientNode(Seq(TLMasterPortParameters.v2(Seq(TLMasterParameters.v2(
    name = "debug",
    emits = TLMasterToSlaveTransferSizes(
      get = TransferSizes(1,axiNode.maxXferBytes),
      putFull = TransferSizes(1,axiNode.maxXferBytes)))))))
  
  // AXI Connections
  (wlink_left.axi2wlNodes(0)._1.axi_tgt_node 
    := AXI4Buffer() 
    := AXI4UserYanker() 
    := AXI4Deinterleaver(axiNode.maxXferBytes) 
    := AXI4IdIndexer(axiNode.idBits) 
    := TLToAXI4() 
    := TLWidthWidget(axiNode.beatBytes) 
    := node)
  
  wlink_right.axi2wlNodes(0)._1.axi_tgt_node := wlink_right.axi2wlNodes(0)._1.axi_ini_node  //looped back
  
  //AXIRAM doesn't take a Seq[AddressSet] so we need to get the size.
  //THIS DOENS'T WORK IF YOU ARE ON SOME BOUNDARY SINCE THIS IS THE MASK NOT REALLY THE SIZE!!!!
  require(wlink_left.axi2wlNodes(0)._2.base == 0x0, s"For the Simple Test Harness I really need the AXI base address at 0. You have it at 0x${wlink_left.axi2wlNodes(0)._2.base.toString(16)}")
  //require(((wlink_left.axi2wlNodes(0)._2.size - 1).asUInt.andR.litValue == 1), s"For the Simple Test Harness I really need the AXI size -1 to be all ones")
  val axiRam   = LazyModule(new AXI4RAM(address= AddressSet(wlink_left.axi2wlNodes(0)._2.base, wlink_left.axi2wlNodes(0)._2.size - 1), beatBytes = axiNode.beatBytes))
  axiRam.node := wlink_left.axi2wlNodes(0)._1.axi_ini_node
  
  
  
  
  apbXbar.node          := ApbPort
  wlink_left.xbar.node  := apbXbar.node
  wlink_right.xbar.node := apbXbar.node
  
  lazy val module = new WlinkSimpleTestHarnessImp(this)
  
}


class WlinkSimpleTestHarnessImp(override val wrapper: WlinkSimpleTestHarness)(implicit p: Parameters) extends LazyModuleImp(wrapper) with RequireAsyncReset{
    val hsclk         = IO(Input (Bool()))
    val finished      = IO(Output(Bool()))
    val error         = IO(Output(Bool()))
    
    
    val finished_reg_in   = Wire(Bool())
    val finished_reg      = RegNext(finished_reg_in, false.B)
    finished              := finished_reg
    
    val error_reg_in      = Wire(Bool())
    
    val error_reg         = RegNext(error_reg_in, false.B)
    error                 := error_reg
    
    val count_in          = Wire(UInt(32.W))
    val count             = RegNext(count_in, false.B)
    
    //Delay the reset for Verilator since it only does the first clock cycle
    val reset_count_in    = Wire(UInt(4.W))
    val reset_count       = RegNext(reset_count_in, "hf".U)
    reset_count_in        := Mux(reset_count === 0.U, 0.U, reset_count - 1.U)
    val delayed_reset     = (reset_count =/= 0.U)
        
    val nstate            = WireInit(THState.IDLE)
    val state             = RegNext(nstate, THState.IDLE)
    
    
    val valid_in          = Wire(Bool())
    val valid             = RegNext(valid_in, false.B)
    val advance_lfsr_in   = Wire(Bool())
    val advance_lfsr      = RegNext(advance_lfsr_in, false.B)
    
    
    val src  = LFSR(wrapper.axiNode.idBits, advance_lfsr)
    val addr = LFSR(log2Ceil(wrapper.wlink_left.axi2wlNodes(0)._2.size - 1), advance_lfsr)
    val size = (log2Ceil(wrapper.axiNode.beatBytes)).asUInt
    val data = LFSR(wrapper.axiNode.beatBytes*8, advance_lfsr)
    val (tl, edge)  = wrapper.node.out(0)
    val (_,  gbits) = edge.Get(src, addr, size)
    val (_, pfbits) = edge.Put(src, addr, size, data)
    
    
    
    nstate                := state
    count_in              := count
    valid_in              := false.B
    advance_lfsr_in       := false.B
    error_reg_in          := error_reg
    finished_reg_in       := false.B
    
    when(state === THState.IDLE){
      nstate              := THState.WRITE
      valid_in            := true.B
    }.elsewhen(state === THState.WRITE){
      valid_in            := true.B
      tl.a.bits           := pfbits
      when(tl.a.ready){
        valid_in          := false.B
        nstate            := THState.WRITE_RESP
      }
    }.elsewhen(state === THState.WRITE_RESP){
      when(tl.d.valid){
        valid_in          := true.B
        nstate            := THState.READ
      }
    }.elsewhen(state === THState.READ){
      valid_in            := true.B
      tl.a.bits           := gbits
      when(tl.a.ready){
        valid_in          := false.B
        nstate            := THState.READ_RESP
      }
    }.elsewhen(state === THState.READ_RESP){
      when(tl.d.valid){
        when(tl.d.bits.data =/= data) { error_reg_in := true.B }
        //when(tl.d.bits.source =/= src){ error_reg_in := true.B }
        count_in          := count + 1.U
        nstate            := Mux(count === 100.U, THState.DONE, THState.IDLE)
        advance_lfsr_in   := true.B
      }
    }.elsewhen(state === THState.DONE){
      finished_reg_in     := true.B
    }
    
    
    tl.a.valid := valid
    tl.d.ready := true.B
    
    //unused here
    tl.b.ready := false.B
    tl.c.valid := false.B
    tl.e.valid := false.B
    
    //---------------------------------
    // Wlink Connections
    //---------------------------------
    val wlink_left_user = wrapper.wlink_left.module.user.asInstanceOf[WlinkGPIOPHYUserBundle]
    wlink_left_user.hsclk                   := hsclk
    wrapper.wlink_left.module.app_clk       := clock.asBool
    wrapper.wlink_left.module.app_clk_reset := delayed_reset
    wrapper.wlink_left.module.por_reset     := delayed_reset
    wrapper.wlink_left.module.sb_reset_in   := false.B
    dontTouch(wrapper.wlink_left.module.scan)
    
    val wlink_right_user = wrapper.wlink_right.module.user.asInstanceOf[WlinkGPIOPHYUserBundle]
    wlink_right_user.hsclk                  := hsclk
    wrapper.wlink_right.module.app_clk      := clock.asBool
    wrapper.wlink_right.module.app_clk_reset:= delayed_reset
    wrapper.wlink_right.module.por_reset    := delayed_reset
    wrapper.wlink_right.module.sb_reset_in  := false.B
    dontTouch(wrapper.wlink_right.module.scan)
    
    val wlink_left_pad  = wrapper.wlink_left.module.pad.asInstanceOf[WavD2DGpioBumpBundle]
    val wlink_right_pad = wrapper.wlink_right.module.pad.asInstanceOf[WavD2DGpioBumpBundle]
    
    require(wlink_left_pad.numTxLanes  == wlink_right_pad.numRxLanes, s"Left Tx lanes (${wlink_left_pad.numTxLanes}) doesn't match Right Rx Lanes (${wlink_right_pad.numRxLanes})")
    require(wlink_right_pad.numTxLanes == wlink_left_pad.numRxLanes,  s"Right Tx lanes (${wlink_left_pad.numTxLanes}) doesn't match Left Rx Lanes (${wlink_right_pad.numRxLanes})")
    
    wlink_right_pad.clk_rx := wlink_left_pad.clk_tx
    wlink_left_pad.clk_rx  := wlink_right_pad.clk_tx
    
    for(i <- 0 until wlink_left_pad.numTxLanes){
      wlink_right_pad.rx(i) := wlink_left_pad.tx(i)
    }
    
    for(i <- 0 until wlink_right_pad.numTxLanes){
      wlink_left_pad.rx(i) := wlink_right_pad.tx(i)
    }
}

object WlinkTHGen extends App {  
  
  case class WlinkTHConfig(
    outputDir   : String = "./output",
    configName  : String = "wav.wlink.AXI32bit1LaneWlinkTestConfig")
  
  import scopt.OParser
  val builder = OParser.builder[WlinkTHConfig]
  val parser1 = {
    import builder._
    OParser.sequence(
      programName("WlinkTHGen"),
      head("WlinkTHGen", "0.1"),
      // option -f, --foo
      opt[String]('o', "outputdir")
        .action((x, c) => c.copy(outputDir = x))
        .text("output directory"),
      
      opt[String]('c', "configName")
        .action((x, c) => c.copy(configName = x))
        .text("config name <package>.<config>"),
    )
  }
  
  OParser.parse(parser1, args, WlinkTHConfig()) match {
    case Some(config) => {
      implicit val p: Parameters = WavConfigUtil.getConfig(Seq(config.configName))
  
      val verilog = (new ChiselStage).emitVerilog(
        LazyModule(new WlinkSimpleTestHarness()(p)).module,

        //args
        Array("--target-dir", config.outputDir)
      )
    }
    case _ => {
      System.exit(1)
    }
  }
}


