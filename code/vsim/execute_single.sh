./DefaultConfig-sim-debug +vcd +vcd_name=output/hello.vcd +max-cycles=100000000 +load=/home/zaepo/iaikgit/2015_master_jantscher/code/fpga/board/kc705/examples/hello.hex | /home/zaepo/iaikgit/2015_master_jantscher/code/riscv/bin/spike-dasm  >output/hello.verilator.out && [ $PIPESTATUS -eq 0 ]
