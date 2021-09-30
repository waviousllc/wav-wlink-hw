Wavious Wlink
========================
The Wavious Wlink defines a low latency, packet based, layered architecture for communicating between two chiplets. Wlink
exposes on-chip application protocols and converts these for transmission across chiplets via various physical layer
implementations. Wlink is highly configurable, allowing users to communicate between chiplets utilizing current and
future on-chip application protocols. Wlink allows user to define implementation specific physical layers without
the need to change RTL for the controller infrastructure

Documentation
---------------
https://wlink.readthedocs.io/en/latest/



Project Requirements
---------------------

* make
* java
* sbt
* icarus verilog (if running simulations)
  
  * Please use version 11.0 or later

*gtkwave with compression packages for viewing waves


Getting Started
------------------

::
  
  git clone <this repo>
  cd wav-wlink-hw
  git submodule update --init --recursive 

Create your first Wlink Design!
--------------------------------
If this is your first run, the initial build may take 5-10 minutes depending on your 
CPU setup.

::
  
  make wlink 
  
Will generate a simple wlink design that has a 1lane GPIO Phy and 32bit AXI ports.
Output RTL will be located in the wav.wlink.AXI32bit1LaneWlinkTestConfig/ directory.
Wlink.v is top level RTL file.


Run a simulation!
--------------------------------

::
  
  make testharness
  
Will generate the WlinkSimpleTestHarness that instantiates two Wlink instances back to
back. The test harness will generate some random AXI reads/writes and compare the data.
You can run the simple testbench by doing the following:

::

  cd verif/
  ./run.sh -o ../wav.wlink.AXI32bit1LaneWlinkTestConfig/


See an issue? Have a question?
--------------------------------
Feel free to submit an issue! Please note that we are a small team so responses may not be immediate.
