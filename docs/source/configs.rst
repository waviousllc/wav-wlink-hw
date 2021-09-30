Wlink Configs
================
Wlink utilizes ``Configs`` for describing how each instance of Wlink looks. Each ``Config``
can be different to target a different instance of Wlink. Each ``Config`` is generally made
up of several smaller config fragments. These are individual fragments that usually target
a certain portion of the design.

Let's take a look at a simple config fragment

.. code-block :: scala

  class WithWlinkDisableTLMonitors extends  Config((site, here, up) => {
    case MonitorsEnabled => false
  })


This config fragment disables TileLink Monitors by setting the ``MonitorsEnabled`` key to false.

One nice feature about ``Configs`` is that you can declare several fragments in one and then extend
from that fragment for generating a larger ``Config``. Let's look at the ``BaseWlinkConfig`` which
we usually extend from.

.. code-block :: scala

  class BaseWlinkConfig extends Config(
    new WithWlinkDisableTLMonitors ++
    new WithWlinkOnlyGen ++
    new WithWavComponentPrefix("wlink")
  )


Here we have defined the ``BaseWlinkConfig``. It starts with the ``WithWavComponentPrefix`` config fragment.
We then add ``WithWlinkOnlyGen`` to indicate we are generating Wlink as the top level component, and
finally we add our ``WithWlinkDisableTLMonitors``.

This ``Config`` doesn't seem all that interesting, so let's see what else we can create.

WithWlinkGPIOAXIConfig Example
------------------------------
Let's say I want to use Wlink with the basic GPIO-based PHY and AXI. Depending on my implementation
I want to be able to change the number of lanes and possibly the AXI parameters. Let's create a config
fragment that gives some configurability.

.. code-block :: scala

  class WithWlinkGPIOAXIConfig(
    numTxLanes  : Int = 1,
    numRxLanes  : Int = 1,
    size        : BigInt = 0x100000,
    beatBytes   : Int = 4,
    idBits      : Int = 4,
  ) extends Config((site, here, up) => {
  
    case WlinkParamsKey => WlinkParams(
      phyParams = WlinkPHYGPIOExampleParams(
        numTxLanes = numTxLanes,
        numRxLanes = numRxLanes,
      ),
      axiParams = Some(Seq(WlinkAxiParams(
        base = 0x0,
        size = size,
        beatBytes = beatBytes,
        idBits = idBits)))
    )
  })


This config fragment allows a user to pass the lanes, size, beatBytes, and id size anytime they mixin 
this config fragment. Let's look below at how we can create two separate instances of the Wlink.



.. code-block :: scala

  class Wlink1LaneAXI32bitConfig extends Config(
    new WithWlinkGPIOAXIConfig(
      numTxLanes = 1,
      numRxLanes = 1,
      beatBytes  = 4) ++
    new BaseWlinkConfig
  )
  
  class Wlink1LaneAXI64bitConfig extends Config(
    new WithWlinkGPIOAXIConfig(
      numTxLanes = 1,
      numRxLanes = 1,
      beatBytes  = 8) ++
    new BaseWlinkConfig
  )
  
  
  class Wlink8LaneAXI256bitConfig extends Config(
    new WithWlinkGPIOAXIConfig(
      numTxLanes = 8,
      numRxLanes = 8,
      beatBytes  = 32) ++
    new BaseWlinkConfig
  )
  


For more details on ``Configs`` it is strongly recommend to look over the Chipyard project

* https://chipyard.readthedocs.io/en/latest/Chipyard-Basics/Configs-Parameters-Mixins.html
* https://chipyard.readthedocs.io/en/latest/Advanced-Concepts/CDEs.html




  
.. generated using get_rst.py by sbridges at September/30/2021  07:54:32


