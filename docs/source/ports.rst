Integration/Ports
====================
Since each implementation of Wlink is specific to the application, the ports will differ. There are certain ports
that are generally always available. Below is a list of the ports

.. table:: 
    :widths: 20 10 10 50
    
    ========================== ===========  =========== ==============================================================================================================
    Port Name                  Direction    Width       Description
    ========================== ===========  =========== ==============================================================================================================
    **DFT**
    ------------------------------------------------------------------------------------------------------------------------------------------------------------------
    scan_mode                  input        1           DFT Scan mode enable                                             
    scan_asyncrst_ctrl         input        1           DFT Scan mode reset control                                            
    scan_clk                   input        1           Clock for DFT Scan mode                                             
    scan_shift                 input        1           DFT Scan shift signal                                             
    scan_in                    input        1           DFT Scan mode data input                                             
    scan_out                   output       1           DFT Scan mode data output

    **APB Configuration Bus**
    ------------------------------------------------------------------------------------------------------------------------------------------------------------------
    apb_clk                    input        1           APB Clock **only valid for this configuration bus**
    apb_reset                  input        1           APB Reset **only valid for this configuration bus**
    apbport_0_psel             input        1                                                        
    apbport_0_penable          input        1                                                        
    apbport_0_pwrite           input        1                                                        
    apbport_0_paddr            input        [21:0]      Size changes based on Register Nodes in the design and number of application layers added                                            
    apbport_0_pprot            input        [2:0]                                                    
    apbport_0_pwdata           input        [31:0]                                                   
    apbport_0_pstrb            input        [3:0]                                                    
    apbport_0_pready           output       1                                                        
    apbport_0_pslverr          output       1                                                        
    apbport_0_prdata           output       [31:0]  
    
    **Sideband**
    ------------------------------------------------------------------------------------------------------------------------------------------------------------------
    sb_reset_in                input        1           Sideband Reset in signal (if sideband resets are not used/required, can tie to 1'b0)                                             
    sb_reset_out               output       1           Sideband Reset out signal - indicates the link has hit a condition where data cannot be reliably received
    sb_wake                    output       1           Sideband Wake - Indicates the link has data to send
                                                       
    **Additional**
    ------------------------------------------------------------------------------------------------------------------------------------------------------------------                                                         
    por_reset                  input        1           Main power on reset                                             
    app_clk                    input        1           Application Clock for application nodes                          
    app_clk_reset              input        1           Application Reset for application nodes                                             
    interrupt                  output       1           Interrupt signal
                                                           
    **User/Pad**
    ------------------------------------------------------------------------------------------------------------------------------------------------------------------
    user_*                     Varies       Varies      ``user`` Bundle signals defined for the specific PHY implementation
    pad_*                      Varies       Varies      ``pad`` Bundle signals defined for the specific PHY implementation
    
    ========================== ===========  =========== ==============================================================================================================
    
    
Application Layer Ports
---------------------------

AXI Ports
+++++++++++
Any time an AXI application layer is added, an AXI Target and Initiator is attached to the system.
AXI port naming is based on the ``name`` parameter used in the ``WlinkAxiParams`` to allow for 
custom AXI port prefixing. The names will follow this format

::
  
  <WlinkAxiParams.name>_<[ini|tgt]>_<channel>_<signal>


Below is an example where the ``WlinkAxiParams.name`` is set to ``axi1``

.. table:: 
    :widths: 20 10 10 50
    
    ========================== ===========  =========== ==============================================================================================================
    Port Name                  Direction    Width       Description
    ========================== ===========  =========== ==============================================================================================================
    axi1_ini_0_aw_ready        input        1                                                        
    axi1_ini_0_aw_valid        output       1                                                        
    axi1_ini_0_aw_bits_id      output       Varies      Based on ``WlinkAxiParams.idBits``                                                
    axi1_ini_0_aw_bits_addr    output       Varies      Based on ``WlinkAxiParams.size``                                                
    axi1_ini_0_aw_bits_len     output       [7:0]                                                    
    axi1_ini_0_aw_bits_size    output       [2:0]                                                    
    axi1_ini_0_aw_bits_burst   output       [1:0]                                                    
    axi1_ini_0_aw_bits_lock    output       1                                                        
    axi1_ini_0_aw_bits_cache   output       [3:0]                                                    
    axi1_ini_0_aw_bits_prot    output       [2:0]                                                    
    axi1_ini_0_aw_bits_qos     output       [3:0]                                                    
    axi1_ini_0_w_ready         input        1                                                        
    axi1_ini_0_w_valid         output       1                                                        
    axi1_ini_0_w_bits_data     output       Varies      Based on ``WlinkAxiParams.beatBytes``                                                   
    axi1_ini_0_w_bits_strb     output       Varies      Based on ``WlinkAxiParams.beatBytes``        
    axi1_ini_0_w_bits_last     output       1                                                        
    axi1_ini_0_b_ready         output       1                                                        
    axi1_ini_0_b_valid         input        1                                                        
    axi1_ini_0_b_bits_id       input        Varies      Based on ``WlinkAxiParams.idBits``       
    axi1_ini_0_b_bits_resp     input        [1:0]                                                    
    axi1_ini_0_ar_ready        input        1                                                        
    axi1_ini_0_ar_valid        output       1                                                        
    axi1_ini_0_ar_bits_id      output       Varies      Based on ``WlinkAxiParams.idBits``      
    axi1_ini_0_ar_bits_addr    output       Varies      Based on ``WlinkAxiParams.size``        
    axi1_ini_0_ar_bits_len     output       [7:0]                                                    
    axi1_ini_0_ar_bits_size    output       [2:0]                                                    
    axi1_ini_0_ar_bits_burst   output       [1:0]                                                    
    axi1_ini_0_ar_bits_lock    output       1                                                        
    axi1_ini_0_ar_bits_cache   output       [3:0]                                                    
    axi1_ini_0_ar_bits_prot    output       [2:0]                                                    
    axi1_ini_0_ar_bits_qos     output       [3:0]                                                    
    axi1_ini_0_r_ready         output       1                                                        
    axi1_ini_0_r_valid         input        1                                                        
    axi1_ini_0_r_bits_id       input        Varies      Based on ``WlinkAxiParams.idBits``                 
    axi1_ini_0_r_bits_data     input        Varies      Based on ``WlinkAxiParams.beatBytes``      
    axi1_ini_0_r_bits_resp     input        [1:0]                                                    
    axi1_ini_0_r_bits_last     input        1                                                        
    axi1_tgt_0_aw_ready        output       1                                                        
    axi1_tgt_0_aw_valid        input        1                                                        
    axi1_tgt_0_aw_bits_id      input        Varies      Based on ``WlinkAxiParams.idBits``      
    axi1_tgt_0_aw_bits_addr    input        Varies      Based on ``WlinkAxiParams.size``        
    axi1_tgt_0_aw_bits_len     input        [7:0]                                                    
    axi1_tgt_0_aw_bits_size    input        [2:0]                                                    
    axi1_tgt_0_aw_bits_burst   input        [1:0]                                                    
    axi1_tgt_0_aw_bits_lock    input        1                                                        
    axi1_tgt_0_aw_bits_cache   input        [3:0]                                                    
    axi1_tgt_0_aw_bits_prot    input        [2:0]                                                    
    axi1_tgt_0_aw_bits_qos     input        [3:0]                                                    
    axi1_tgt_0_w_ready         output       1                                                        
    axi1_tgt_0_w_valid         input        1                                                        
    axi1_tgt_0_w_bits_data     input        Varies      Based on ``WlinkAxiParams.beatBytes``
    axi1_tgt_0_w_bits_strb     input        Varies      Based on ``WlinkAxiParams.beatBytes``      
    axi1_tgt_0_w_bits_last     input        1                                                        
    axi1_tgt_0_b_ready         input        1                                                        
    axi1_tgt_0_b_valid         output       1                                                        
    axi1_tgt_0_b_bits_id       output       Varies      Based on ``WlinkAxiParams.idBits``         
    axi1_tgt_0_b_bits_resp     output       [1:0]                                                    
    axi1_tgt_0_ar_ready        output       1                                                        
    axi1_tgt_0_ar_valid        input        1                                                        
    axi1_tgt_0_ar_bits_id      input        Varies      Based on ``WlinkAxiParams.idBits``
    axi1_tgt_0_ar_bits_addr    input        Varies      Based on ``WlinkAxiParams.size``  
    axi1_tgt_0_ar_bits_len     input        [7:0]                                                    
    axi1_tgt_0_ar_bits_size    input        [2:0]                                                    
    axi1_tgt_0_ar_bits_burst   input        [1:0]                                                    
    axi1_tgt_0_ar_bits_lock    input        1                                                        
    axi1_tgt_0_ar_bits_cache   input        [3:0]                                                    
    axi1_tgt_0_ar_bits_prot    input        [2:0]                                                    
    axi1_tgt_0_ar_bits_qos     input        [3:0]                                                    
    axi1_tgt_0_r_ready         input        1                                                        
    axi1_tgt_0_r_valid         output       1                                                        
    axi1_tgt_0_r_bits_id       output       Varies      Based on ``WlinkAxiParams.idBits``       
    axi1_tgt_0_r_bits_data     output       Varies      Based on ``WlinkAxiParams.beatBytes``           
    axi1_tgt_0_r_bits_resp     output       [1:0]                                                    
    axi1_tgt_0_r_bits_last     output       1     
    
    ========================== ===========  =========== ==============================================================================================================


.. note ::

  If Wlink is instantiated inside of another larger Chisel design, the port names at the Wlink boundary
  may differ.




APB Ports
+++++++++++
Unlike AXI Ports, APB ports are selected based on Target or Initiator. 

::
  
  <WlinkApbTgtParams.name>_tgt_<signal>
  <WlinkApbIniParams.name>_ini_<signal>


Below is an example where the ``WlinkApbTgtParams.name`` is set to ``apb``

.. table:: 
    :widths: 20 10 10 50
    
    ========================== ===========  =========== ==============================================================================================================
    Port Name                  Direction    Width       Description
    ========================== ===========  =========== ==============================================================================================================
    apb_tgt_0_psel             input        1                                                        
    apb_tgt_0_penable          input        1                                                        
    apb_tgt_0_pwrite           input        1                                                        
    apb_tgt_0_paddr            input        Varies      Based on ``WlinkApbTgtParams.size``                                   
    apb_tgt_0_pprot            input        [2:0]                                                    
    apb_tgt_0_pwdata           input        [31:0]                                                   
    apb_tgt_0_pstrb            input        [3:0]                                                    
    apb_tgt_0_pready           output       1                                                        
    apb_tgt_0_pslverr          output       1                                                        
    apb_tgt_0_prdata           output       [31:0]
    
    ========================== ===========  =========== ==============================================================================================================


.. note ::

  If Wlink is instantiated inside of another larger Chisel design, the port names at the Wlink boundary
  may differ.
    
                                                       
General Bus Ports
+++++++++++++++++++
General Bus application layers provide a large bus in and out of the Wlink.


Below is an example where the ``WlinkGeneralBusParams.name`` is set to ``gb``

.. table:: 
    :widths: 20 10 10 50
    
    ========================== ===========  =========== ==============================================================================================================
    Port Name                  Direction    Width       Description
    ========================== ===========  =========== ==============================================================================================================
    gb_in                      input        Varies      Based on ``WlinkGeneralBusParams .width``                                                  
    gb_out                     output       Varies      Based on ``WlinkGeneralBusParams .width``
    
    ========================== ===========  =========== ==============================================================================================================


.. note ::

  If Wlink is instantiated inside of another larger Chisel design, the port names at the Wlink boundary
