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

/**
  *   Main PHY Bundles for Tx and Rx
  *   All PHYs should conform to this. Any additional signals (debug, etc.)
  *   should be part of the user bundle defined below
  */
class WlinkPHYTxBundle(val linkDataWidth: Int) extends Bundle{
  val tx_en               = Input (Bool())
  val tx_ready            = Output(Bool())
  val tx_link_data        = Input (UInt(linkDataWidth.W))
  val tx_data_valid       = Input (Bool())
  val tx_active_lanes     = Input (UInt(8.W))
  val tx_link_clk         = Output(Bool())
}

class WlinkPHYRxBundle(val linkDataWidth: Int) extends Bundle{
  val rx_enter_lp         = Input (Bool())
  val rx_link_data        = Output(UInt(linkDataWidth.W))
  val rx_data_valid       = Output(Bool())
  val rx_active_lanes     = Input (UInt(8.W))
  val rx_link_clk         = Output(Bool())
}


/**
  *   Base PHY Parameter Class that can be used for custom PHY implementations
  *   It includes the basic details of the PHY that are required by Wlink at
  *   the link layer level (lanes, datawidth, baseaddr)
  */
abstract class WlinkPHYBaseParams{
  def phyType       : (Parameters) => WlinkPHYBase 
  def numTxLanes    : Int
  def numRxLanes    : Int
  def baseAddr      : BigInt
  def phyDataWidth  : Int
  
  def phyVersion    : UInt
  def phyVersionStr : String
}

/**
  *   Base Class that all PHY Instances should extend from
  */
abstract class WlinkPHYBase()(implicit p: Parameters) extends LazyModule{
  
  val node        = APBIdentityNode()
  
  lazy val module = new LazyModuleImp(this) with RequireAsyncReset{
    val scan      = new WavScanBundle
    val por_reset = IO(Input (Bool()))
    val link_tx   = IO(new WlinkPHYTxBundle(8))
    val link_rx   = IO(new WlinkPHYRxBundle(8))
    
    val user      = IO(new Bundle{})
    val pad       = IO(new Bundle{})
  }
}


/**
  *   GPIO Example
  */ 

case class WlinkPHYGPIOExampleParams(
  phyType       : (Parameters) => WlinkGPIOPHY = (p: Parameters) => new WlinkGPIOPHY()(p),
  numTxLanes    : Int = 1,
  numRxLanes    : Int = 1,
  baseAddr      : BigInt = 0x0,
  phyDataWidth  : Int = 16,
  phyVersion    : UInt = 1.U,
  phyVersionStr : String = "GPIO",
  
  someCustomParam: String = "Making a GPIO PHY"
  
) extends WlinkPHYBaseParams

class WlinkGPIOPHYUserBundle extends Bundle{
  val hsclk   = Input (Bool())
}

class WlinkGPIOPHY()(implicit p: Parameters) extends WlinkPHYBase{
  
  val params  : WlinkPHYGPIOExampleParams = p(WlinkParamsKey).phyParams.asInstanceOf[WlinkPHYGPIOExampleParams]
  
  val gpio    = LazyModule(new WavD2DGpio(numTxLanes=params.numTxLanes, 
                                          numRxLanes=params.numRxLanes,
                                          baseAddr=params.baseAddr, 
                                          dataWidth=params.phyDataWidth)(p))
  
  gpio.node   := node
  
  println(params.someCustomParam)
  
  override lazy val module = new LazyModuleImp(this) with RequireAsyncReset{
    val scan      = IO(new WavScanBundle)
    val por_reset = IO(Input (Bool()))
    val link_tx   = IO(new WlinkPHYTxBundle(params.numTxLanes * params.phyDataWidth))
    val link_rx   = IO(new WlinkPHYRxBundle(params.numRxLanes * params.phyDataWidth))
    
    val pad       = IO(new WavD2DGpioBumpBundle(params.numTxLanes, params.numRxLanes))
    val user      = IO(new WlinkGPIOPHYUserBundle)    
    
    gpio.module.io.por_reset   := por_reset
    gpio.module.io.hsclk       := user.hsclk
    gpio.module.io.link_tx     <> link_tx
    gpio.module.io.link_rx     <> link_rx
    gpio.module.io.pad         <> pad
    gpio.module.io.scan.connectScan(scan)
    
  }
}
