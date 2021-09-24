package wav.wlink

import wav.common._

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



//=====================================================
// TX
//=====================================================
case class WlinkLLTxBundleParameters(dataWidth: Int){}

object WlinkLLTxBundleParameters{
  def apply(source: WlinkLLSourceTxPortParameters) = {
    new WlinkLLTxBundleParameters(source.width)
  }
  
  def apply(sink: WlinkLLSinkTxPortParameters) = {
    new WlinkLLTxBundleParameters(sink.width)
  }
  
  def apply(source: WlinkLLSourceTxPortParameters, sink: WlinkLLSinkTxPortParameters) = {
    //require(source.width == sink.width, s"Source width (${source.width}) and Sink width (${sink.width}) do not match!")
    new WlinkLLTxBundleParameters(source.width)
    //new WlinkLLTxBundleParameters(sink.width)
    
    //TEMP WHILE I TEST SOME THINGS
   // new WlinkLLTxBundleParameters(source.width.max(sink.width))
  }
  
}

//From the point of view of the SourceNodes
class WlinkLLTxBundle(val params: WlinkLLTxBundleParameters) extends Bundle{
  val sop       = Output(Bool())
  val data_id   = Output(UInt(8.W))
  val word_count= Output(UInt(16.W))
  val data      = Output(UInt(params.dataWidth.W))
  val crc       = Output(UInt(16.W))
  val advance   = Input (Bool())
}



object WlinkLLTxBundle{
  def apply(params: WlinkLLTxBundleParameters) = new WlinkLLTxBundle(params)
}


//Defines the Source for a TX Node
case class WlinkLLSourceTxParameters(
  ids   : Seq[Int],     //All IDs used
  name  : String)       //Associated name

//Defines a "port" for a particular TX Source. Contains a 
//list of all of the sources that are aggregated on this port
case class WlinkLLSourceTxPortParameters(
  sources : Seq[WlinkLLSourceTxParameters],
  width   : Int,          //Max bits needed (for largest packet)
){}



//Defines the Sink for a TX Node
case class WlinkLLSinkTxParameters(
  ids   : Seq[Int],     //All IDs used
  name  : String)       //Associated name
  
case class WlinkLLSinkTxPortParameters(
  sinks : Seq[WlinkLLSinkTxParameters],
  width : Int,          
){}




case class WlinkLLTxEdgeParameters(
  source      : WlinkLLSourceTxPortParameters,
  sink        : WlinkLLSinkTxPortParameters,
  params      : Parameters,
  sourceInfo  : SourceInfo
){
  //
  val bundle = WlinkLLTxBundleParameters(source, sink)
  
}


// NODE Implementation
object WlinkLLTxImp extends NodeImp[WlinkLLSourceTxPortParameters, WlinkLLSinkTxPortParameters, WlinkLLTxEdgeParameters, WlinkLLTxEdgeParameters, WlinkLLTxBundle] {
  def edgeO(pd: WlinkLLSourceTxPortParameters, pu: WlinkLLSinkTxPortParameters, p: Parameters, sourceInfo: SourceInfo) =  {
    WlinkLLTxEdgeParameters(pd, pu, p, sourceInfo)
  }
  def edgeI(pd: WlinkLLSourceTxPortParameters, pu: WlinkLLSinkTxPortParameters, p: Parameters, sourceInfo: SourceInfo) =  {
    WlinkLLTxEdgeParameters(pd, pu, p, sourceInfo)
  }
  
  def bundleO(eo: WlinkLLTxEdgeParameters) = WlinkLLTxBundle(eo.bundle)
  def bundleI(ei: WlinkLLTxEdgeParameters) = Flipped(WlinkLLTxBundle(ei.bundle))
  
  def render(ei: WlinkLLTxEdgeParameters) = RenderedEdge(colour = "#ff00ff", label = s"${ei.sink.width}")

  
}


case class WlinkTxSourceNode(portParams: Seq[WlinkLLSourceTxPortParameters])(implicit valName: ValName) extends SourceNode(WlinkLLTxImp)(portParams)

case class WlinkTxSinkNode  (portParams: Seq[WlinkLLSinkTxPortParameters])  (implicit valName: ValName) extends SinkNode(WlinkLLTxImp)(portParams)

//Adapter does a one to one
case class WlinkTxAdapterNode (
  sourceFn: WlinkLLSourceTxPortParameters => WlinkLLSourceTxPortParameters,
  sinkFn:   WlinkLLSinkTxPortParameters   => WlinkLLSinkTxPortParameters  )(
  implicit valName: ValName)
  extends AdapterNode(WlinkLLTxImp)(sourceFn, sinkFn)

//Nexus is N to 1
case class WlinkTxNexusNode (
  sourceFn: Seq[WlinkLLSourceTxPortParameters] => WlinkLLSourceTxPortParameters,
  sinkFn:   Seq[WlinkLLSinkTxPortParameters]   => WlinkLLSinkTxPortParameters  )(
  implicit valName: ValName)
  extends NexusNode(WlinkLLTxImp)(sourceFn, sinkFn)

//should we create a different implemtation for 





//=====================================================
// RX
//=====================================================
case class WlinkLLRxBundleParameters(dataWidth: Int){}

object WlinkLLRxBundleParameters{
  def apply(source: WlinkLLSourceRxPortParameters) = {
    new WlinkLLTxBundleParameters(source.width)
  }
  
  def apply(sink: WlinkLLSinkRxPortParameters) = {
    new WlinkLLTxBundleParameters(sink.width)
  }
  
  def apply(source: WlinkLLSourceRxPortParameters, sink: WlinkLLSinkRxPortParameters) = {
    //require(source.width == sink.width, s"Source width (${source.width}) and Sink width (${sink.width}) do not match!")
    //new WlinkLLRxBundleParameters(source.width)
    
    //TEMP WHILE I TEST SOME THINGS
    //println(s"source : ${source} \nsink: ${sink}")
    //new WlinkLLRxBundleParameters(source.width.max(sink.width))
    new WlinkLLRxBundleParameters(sink.width)
  }
  
}

//From the point of view of the SourceNodes
class WlinkLLRxBundle(val params: WlinkLLRxBundleParameters) extends Bundle{
  val sop         = Output(Bool())
  val data_id     = Output(UInt(8.W))
  val word_count  = Output(UInt(16.W))
  val data        = Output(UInt(params.dataWidth.W))
  val valid       = Output(Bool())
  val crc         = Output(UInt(16.W))
}



object WlinkLLRxBundle{
  def apply(params: WlinkLLRxBundleParameters) = new WlinkLLRxBundle(params)
}

//Defines the Source for a RX Node
case class WlinkLLSourceRxParameters(
  ids   : Seq[Int],     //All IDs used
  name  : String)       //Associated name

//Defines a "port" for a particular RX Source. Contains a 
//list of all of the sources that are aggregated on this port
case class WlinkLLSourceRxPortParameters(
  sources : Seq[WlinkLLSourceRxParameters],
  width   : Int,          //Max bits needed (for largest packet)
){}



//Defines the Sink for a RX Node
case class WlinkLLSinkRxParameters(
  ids   : Seq[Int],     //All IDs used
  name  : String)       //Associated name
  
case class WlinkLLSinkRxPortParameters(
  sinks : Seq[WlinkLLSinkRxParameters],   //should only be single?
  width : Int,          
){}




case class WlinkLLRxEdgeParameters(
  source      : WlinkLLSourceRxPortParameters,
  sink        : WlinkLLSinkRxPortParameters,
  params      : Parameters,
  sourceInfo  : SourceInfo
){
  //
  val bundle = WlinkLLRxBundleParameters(source, sink)
  
}


// NODE Implementation
object WlinkLLRxImp extends NodeImp[WlinkLLSourceRxPortParameters, WlinkLLSinkRxPortParameters, WlinkLLRxEdgeParameters, WlinkLLRxEdgeParameters, WlinkLLRxBundle] {
  def edgeO(pd: WlinkLLSourceRxPortParameters, pu: WlinkLLSinkRxPortParameters, p: Parameters, sourceInfo: SourceInfo) =  {
    WlinkLLRxEdgeParameters(pd, pu, p, sourceInfo)
  }
  def edgeI(pd: WlinkLLSourceRxPortParameters, pu: WlinkLLSinkRxPortParameters, p: Parameters, sourceInfo: SourceInfo) =  {
    WlinkLLRxEdgeParameters(pd, pu, p, sourceInfo)
  }
  
  def bundleO(eo: WlinkLLRxEdgeParameters) = WlinkLLRxBundle(eo.bundle)
  def bundleI(ei: WlinkLLRxEdgeParameters) = Flipped(WlinkLLRxBundle(ei.bundle))
  
  def render(ei: WlinkLLRxEdgeParameters) = RenderedEdge(colour = "#00ff00", label = s"${ei.sink.width}")

  
}


case class WlinkRxSourceNode(portParams: Seq[WlinkLLSourceRxPortParameters])(implicit valName: ValName) extends SourceNode(WlinkLLRxImp)(portParams)

case class WlinkRxSinkNode  (portParams: Seq[WlinkLLSinkRxPortParameters])  (implicit valName: ValName) extends SinkNode(WlinkLLRxImp)(portParams)

case class WlinkRxNexusNode (
  sourceFn: Seq[WlinkLLSourceRxPortParameters] => WlinkLLSourceRxPortParameters,
  sinkFn:   Seq[WlinkLLSinkRxPortParameters]   => WlinkLLSinkRxPortParameters  )(
  implicit valName: ValName)
  extends NexusNode(WlinkLLRxImp)(sourceFn, sinkFn)
