./DefaultConfig-sim-debug +vcd +vcd_name=output/dram.vcd +max-cycles=1000000 +load=/home/zaepo/iaikgit/2015_master_jantscher/code/fpga/board/kc705/examples/dram.hex | /home/zaepo/iaikgit/2015_master_jantscher/code/riscv/bin/spike-dasm  >output/dram.verilator.out && [ $PIPESTATUS -eq 0 ]
