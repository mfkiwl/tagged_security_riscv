./DefaultConfig-sim-debug +vcd +vcd_name=output/lbu_test.vcd +max-cycles=1000000 +load=/home/zaepo/iaikgit/2015_master_jantscher/code/fpga/board/kc705/examples/lbu_test.hex | /home/zaepo/iaikgit/2015_master_jantscher/code/riscv/bin/spike-dasm  >output/lbu_test.verilator.out && [ $PIPESTATUS -eq 0 ]
#./DefaultConfig-sim-debug +vcd +vcd_name=/media/zaepo/USB\ DISK/log_files_new/dram.vcd +max-cycles=1000000 +load=/home/zaepo/iaikgit/2015_master_jantscher/code/fpga/board/kc705/examples/dram.hex | /home/zaepo/iaikgit/2015_master_jantscher/code/riscv/bin/spike-dasm  >/media/zaepo/USB\ DISK/log_files_new/dram.verilator.out && [ $PIPESTATUS -eq 0 ]
