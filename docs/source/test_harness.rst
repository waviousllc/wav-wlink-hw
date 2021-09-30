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




  
.. generated using get_rst.py by sbridges at September/30/2021  07:54:32


