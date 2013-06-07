package de.hopp.generator.backends.server.virtex6.ise.sdk;

import static de.hopp.generator.backends.server.virtex6.ise.ISE.sdkSourceDir;
import static de.hopp.generator.backends.server.virtex6.ise.ISEUtils.sdkAppDir;
import static de.hopp.generator.model.Model.*;
import static de.hopp.generator.utils.BoardUtils.*;
import static de.hopp.generator.utils.Model.add;
import static de.hopp.generator.utils.Model.addLines;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import katja.common.NE;
import de.hopp.generator.Configuration;
import de.hopp.generator.ErrorCollection;
import de.hopp.generator.exceptions.ParserError;
import de.hopp.generator.frontend.*;
import de.hopp.generator.frontend.BDLFilePos.Visitor;
import de.hopp.generator.model.MCode;
import de.hopp.generator.model.MFile;
import de.hopp.generator.model.MInclude;
import de.hopp.generator.model.MProcedure;
import de.hopp.generator.model.Strings;
import de.hopp.generator.parser.Block;
import de.hopp.generator.parser.MHS;
import de.hopp.generator.parser.MHSFile;

/**
 * Generation backend for Xilinx SDK.
 *
 * This backend is responsible for board-dependent files required to
 * build up the .elf file.
 * This includes generation of the non-generic, board-dependent sources
 * (i.e. constants and components files) as well as a deployment list
 * of several generic, board-dependent sources (e.g. GPIO devices).
 *
 *
 *
 * Since there seem to be no fundamental changes in the SDK since 14.1,
 * only one backend is provided here, which is assumed to be compatible with all
 * generated bitfiles. If this proves not to be the case, rename the SDK accordingly
 * and provide a structure similar to the XPS backends.
 *
 * @author Thomas Fischer
 */
public class SDK extends Visitor<NE> {

    private final ErrorCollection errors;

    // target folders
    private final File targetDir;
    private final File targetSrc;

    // generic files to copy
    protected Map<File, File> deployFiles;

    // generated files
    protected MFile components;
    protected MFile constants;
    protected MFile scheduler;
    protected MHSFile mssFile;

    // parts of the generated files
    private MProcedure init;
    private MProcedure reset;
    private MProcedure axi_write;
    private MProcedure axi_read;

    // counter variables
    private int axiStreamIdMaster = 0;
    private int axiStreamIdSlave  = 0;

    private int gpiCount = 0;
    private int gpoCount = 0;

    // version strings
    protected String version;

    protected String version_os;
    protected String version_cpu;

    protected String version_intc;
    protected String version_v6_ddrx;
    protected String version_bram_block;
    protected String version_timer_controller;

    protected String version_uartlite;
    protected String version_ethernetlite;
    protected String version_lwip_lib_name;
    protected String version_lwip_lib;

    protected String version_gpio_leds;
    protected String version_gpio_buttons;
    protected String version_gpio_switches;

    protected String version_queue;
    protected String version_resizer;

    public MFile getComponents() {
        return components;
    }

    public MFile getConstants() {
        return constants;
    }

    public MFile getScheduler() {
        return scheduler;
    }

    public MHSFile getMSS() {
        return mssFile;
    }

    public Map<File, File> getFiles() {
        return deployFiles;
    }

    public SDK(Configuration config, ErrorCollection errors) {
        this.errors = errors;

        // initialise version strings
        version                   = "2.2.0";

        version_os                = "3.08.a";
        version_cpu               = "1.14.a";

        version_bram_block        = "3.01.a";
        version_intc              = "2.05.a";
        version_timer_controller  = "2.04.a";
        version_v6_ddrx           = "2.00.a";

        version_uartlite          = "2.00.a";
        version_ethernetlite      = "3.03.a";

        version_lwip_lib_name     = "lwip140";
        version_lwip_lib          = "1.03.a";

        version_gpio_leds         = "3.00.a";
        version_gpio_buttons      = "3.00.a";
        version_gpio_switches     = "3.00.a";

        version_queue             = "1.00.a";
        version_resizer           = "1.00.a";

        // set directories
        this.targetDir = sdkAppDir(config);
        targetSrc = new File(targetDir, "src");

        // setup files and methods
        deployFiles = new HashMap<File, File>();
        setupFiles();
        setupMethods();
        setupMSS();
    }

    private void setupFiles() {
        components = MFile(MDocumentation(Strings(
                "Contains component-specific initialisation and processing procedures."
            )), "components", new File(targetSrc, "components").getPath(), MPreProcDirs(),
            MStructs(), MEnums(), MAttributes(), MProcedures(), MClasses());
        constants  = MFile(MDocumentation(Strings(
                "Defines several constants used by the server.",
                "This includes medium-specific configuration."
            )), "constants", targetSrc.getPath(), MPreProcDirs(),
            MStructs(), MEnums(), MAttributes(), MProcedures(), MClasses());
    }

    private void setupMethods() {
        init  = MProcedure(MDocumentation(Strings(
                "Initialises all components on this board.",
                "This includes gpio components and user-defined IPCores,",
                "but not the communication medium this board is attached with."
            )), MModifiers(), MVoid(), "init_components", MParameters(), MCode(Strings("int status;")));
        reset = MProcedure(MDocumentation(Strings(
                "Resets all components in this board.",
                "This includes gpio components and user-defined IPCores,",
                "but not the communication medium, this board is attached with."
            )), MModifiers(), MVoid(), "reset_components", MParameters(), MCode(Strings()));
        axi_write = MProcedure(MDocumentation(Strings(
                "Write a value to an AXI stream."),
                PARAM("val", "Value to be written to the stream."),
                PARAM("target", "Target stream identifier.")
            ), MModifiers(PRIVATE()), MType("int"), "axi_write", MParameters(
                MParameter(VALUE(), MType("int"), "val"), MParameter(VALUE(), MType("int"), "target")
            ), MCode(Strings(
                "// YES, this is ridiculous... THANKS FOR NOTHING, XILINX!",
                "if(DEBUG) xil_printf(\"\\nwriting to in-going port %d (value: %d) ...\", target, val);",
                "switch(target) {"
            ), MQuoteInclude("fsl.h"), MQuoteInclude("../constants.h")));
        axi_read  = MProcedure(MDocumentation(Strings(
                "Read a value from an AXI stream."),
                PARAM("val", "Pointer to the memory area, where the read value will be stored."),
                PARAM("target", "Target stream identifier.")
            ), MModifiers(PRIVATE()), MType("int"), "axi_read", MParameters(
                MParameter(VALUE(), MPointerType(MType("int")), "val"), MParameter(VALUE(), MType("int"), "target")
            ), MCode(Strings(
                "// YES, this is ridiculous... THANKS FOR NOTHING, XILINX!",
                "if(DEBUG) xil_printf(\"\\nreading from out-going port %d ...\", target);",
                "switch(target) {"
            ), MQuoteInclude("fsl.h"), MQuoteInclude("../constants.h")));
    }

    private void setupMSS() {
        mssFile = MHS.MHSFile(
            MHS.Attributes(
                MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("VERSION", MHS.Ident(version)))
            ), MHS.Block("OS",
                MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("OS_NAME", MHS.Ident("standalone"))),
                MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("OS_VER", MHS.Ident(version_os))),
                MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("PROC_INSTANCE", MHS.Ident("microblaze_0"))),
                MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("STDIN", MHS.Ident("rs232_uart_1"))),
                MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("STDOUT", MHS.Ident("rs232_uart_1")))
            ), MHS.Block("PROCESSOR",
                MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("DRIVER_NAME", MHS.Ident("cpu"))),
                MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("DRIVER_VER", MHS.Ident(version_cpu))),
                MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("HW_INSTANCE", MHS.Ident("microblaze_0")))
            ), MHS.Block("DRIVER",
                MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("DRIVER_NAME", MHS.Ident("tmrctr"))),
                MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("DRIVER_VER", MHS.Ident(version_timer_controller))),
                MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("HW_INSTANCE", MHS.Ident("axi_timer_0")))
            ), MHS.Block("DRIVER",
                MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("DRIVER_NAME", MHS.Ident("v6_ddrx"))),
                MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("DRIVER_VER", MHS.Ident(version_v6_ddrx))),
                MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("HW_INSTANCE", MHS.Ident("ddr3_sdram")))
            ), MHS.Block("DRIVER",
                MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("DRIVER_NAME", MHS.Ident("bram"))),
                MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("DRIVER_VER", MHS.Ident(version_bram_block))),
                MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("HW_INSTANCE", MHS.Ident("microblaze_0_d_bram_ctrl")))
            ), MHS.Block("DRIVER",
                MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("DRIVER_NAME", MHS.Ident("bram"))),
                MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("DRIVER_VER", MHS.Ident(version_bram_block))),
                MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("HW_INSTANCE", MHS.Ident("microblaze_0_i_bram_ctrl")))
            ), MHS.Block("DRIVER",
                MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("DRIVER_NAME", MHS.Ident("intc"))),
                MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("DRIVER_VER", MHS.Ident(version_intc))),
                MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("HW_INSTANCE", MHS.Ident("microblaze_0_intc")))
            ), MHS.Block("DRIVER",
                MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("DRIVER_NAME", MHS.Ident("uartlite"))),
                MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("DRIVER_VER", MHS.Ident(version_uartlite))),
                MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("HW_INSTANCE", MHS.Ident("debug_module")))
            ), MHS.Block("DRIVER",
                MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("DRIVER_NAME", MHS.Ident("uartlite"))),
                MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("DRIVER_VER", MHS.Ident(version_uartlite))),
                MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("HW_INSTANCE", MHS.Ident("rs232_uart_1")))
            )
        );
    }

    // We assume all imports to be accumulated at the parser
    public void visit(ImportsPos  term) { }
    public void visit(BackendsPos term) { }
    public void visit(OptionsPos  term) { }

    public void visit(BDLFilePos board) {

        // add debug constants
        boolean debug = isDebug(board);
        addConst("DEBUG", debug ? "1" : "0", "Indicates, if additional messages should be logged on the console.");
        if(debug) addConst("loopy_print(...)", "xil_printf(__VA_ARGS__)",
            "With the chosen debug level, debug output will be sent over the JTAG cable.",
            MBracketInclude("stdio.h")
        );
        else addConst("loopy_print(...)", "",
            "With disabled debugging, calls to the print method are simply removed."
        );

        // add queue size constant
        addConst("MAX_OUT_SW_QUEUE_SIZE", String.valueOf(maxQueueSize(board)),
                "Maximal size of out-going software queues.");

        // add protocol version constant
        addConst("PROTO_VERSION", "1", "Denotes protocol version, that should be used for sending messages.");

        // visit board components
        visit(board.medium());
        visit(board.scheduler());
        visit(board.gpios());
        visit(board.cores());
        visit(board.insts());

        // add stream count constants
        addConst("IN_STREAM_COUNT", String.valueOf(axiStreamIdMaster), "Number of in-going stream interfaces.");
        addConst("OUT_STREAM_COUNT", String.valueOf(axiStreamIdSlave), "Number of out-going stream interfaces.");

        // add gpio count constants
        addConst("gpi_count", String.valueOf(gpiCount), "Number of gpi components");
        addConst("gpo_count", String.valueOf(gpoCount), "Number of gpo components");

        // add init and reset procedures to the source file
        components = add(components, init);
        components = add(components, reset);

        // finish axi read and write procedures
        axi_write = addLines(axi_write, MCode(Strings(
                "default: xil_printf(\"ERROR: unknown axi stream port %d\", target);",
                "}", "// should be call by value --> reuse the memory address...",
                "int rslt = 1;",
                "fsl_isinvalid(rslt);",
                "if(DEBUG) xil_printf(\" (invalid: %d)\", rslt);",
                "return rslt;")));
        axi_read  = addLines(axi_read,  MCode(Strings(
                "default: xil_printf(\"ERROR: unknown axi stream port %d\", target);",
                "}", "// should be call by value --> reuse the memory address...",
                "if(DEBUG) xil_printf(\"\\n %d\", *val);",
                "int rslt = 1;",
                "fsl_isinvalid(rslt);",
                "if(DEBUG) xil_printf(\" (invalid: %d)\", rslt);",
                "return rslt;")));

        // add axi read and write procedures
        components = add(components, axi_write);
        components = add(components, axi_read);
    }

    public void visit(ETHERNETPos term) {
        // deploy Ethernet medium files
        deployFiles.put(new File(sdkSourceDir, "ethernet.c"), new File(new File(targetSrc, "medium"), "medium.c"));

        // add Ethernet-specific constants
        for(MOptionPos opt : term.opts())
            opt.Switch(new MOptionPos.Switch<String, NE>() {
                @Override
                public String CaseMACPos(MACPos term) {
                    addMAC(term.val().term()); return null;
                }
                @Override
                public String CaseIPPos(IPPos term) {
                    addIP("IP", term.val().term(), "IP address"); return null;
                }
                @Override
                public String CaseMASKPos(MASKPos term) {
                    addIP("MASK", term.val().term(), "network mask"); return null;
                }
                @Override
                public String CaseGATEPos(GATEPos term) {
                    addIP("GW", term.val().term(), "standard gateway"); return null;
                }
                @Override
                public String CasePORTIDPos(PORTIDPos term) {
                    addConst("PORT", term.val().term().toString(), "The port for this boards TCP-connection.");
                    return null;
                }
            });

        // add Ethernet driver and lwip library to bsp
        mssFile = addBlock(mssFile, MHS.Block("DRIVER",
            MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("DRIVER_NAME", MHS.Ident("emaclite"))),
            MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("DRIVER_VER", MHS.Ident(version_ethernetlite))),
            MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("HW_INSTANCE", MHS.Ident("ethernet_lite")))
        ));

        mssFile = addBlock(mssFile, MHS.Block("LIBRARY",
            MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("LIBRARY_NAME", MHS.Ident(version_lwip_lib_name))),
            MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("LIBRARY_VER", MHS.Ident(version_lwip_lib))),
            MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("PROC_INSTANCE", MHS.Ident("microblaze_0")))
        ));
    }

    public void visit(UARTPos term) {

    }

    public void visit(PCIEPos term) {

    }

    public void visit(final GPIOPos gpio) {
        File componentsDir = new File(targetSrc, "components");
        deployFiles.put(new File(sdkSourceDir, "gpio.h"), new File(componentsDir, "gpio.h"));
        deployFiles.put(new File(sdkSourceDir, "gpio.c"), new File(componentsDir, "gpio.c"));

        gpio.direction().termDirection().Switch(new Direction.Switch<String, NE>() {
            public String CaseIN(IN term) {
                // add gpi id definition
                components = add(components, MDef(MDocumentation(Strings(
                        "GPI ID of the " + gpio.name().term() + " component"
                    )), MModifiers(PRIVATE()), "gpi_" + gpio.name().term(), String.valueOf(gpiCount)));

                // add exception handler method
                components = add(components, createExceptionHandler(gpio.term()));

                // generate code for init method
                try {
                    init = addLines(init, initGPI(gpio.term()));
                } catch (ParserError e) { errors.addError(e); }

                // increment gpi count
                gpiCount++;

                return null;
            }

            public String CaseOUT(OUT term) {
                // add gpo id definition
                components = add(components, MDef(MDocumentation(Strings(
                    "GPO ID of the " + gpio.name().term() + " component"
                )), MModifiers(PRIVATE()), "gpo_" + gpio.name().term(), String.valueOf(gpoCount)));

                // generate code for init method
                try {
                    init = addLines(init, initGPO(gpio.term()));
                } catch (ParserError e) { errors.addError(e); }

                // increment gpo count
                gpoCount++;

                return null;
            }
            public String CaseDUAL(DUAL term) {
                throw new RuntimeException("Bi-directional GPIO components not allowed");
            }
        });

        try {
            // add the driver block to the mss file
            mssFile = addBlock(mssFile, MHS.Block("DRIVER",
                MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("DRIVER_NAME", MHS.Ident("gpio"))),
                MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("DRIVER_VER",  MHS.Ident(hwVersion(gpio.term())))),
                MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("HW_INSTANCE", MHS.Ident(hwInstance(gpio.term()))))
            ));
        } catch(ParserError e) {
            errors.addError(e);
        }
    }

    private MProcedure createExceptionHandler(final GPIO gpio) {
        MCode body =
            gpio.callback().Switch(new Code.Switch<MCode, NE>() {
                private final String start = "XGpio *GpioPtr = (XGpio *)CallbackRef;";
                private final Strings end  = Strings("",
                    "// Clear the Interrupt",
                    "XGpio_InterruptClear(GpioPtr, GPIO_CHANNEL1);"
                );

                public MCode CaseDEFAULT(DEFAULT term) {
                    return MCode(Strings(start, "",
                        "// transmit gpi state change to host",
                        "send_gpio(gpi_" + gpio.name() + ", gpio_read(gpi_" + gpio.name() + "));"
                    ).addAll(end),
                    MForwardDecl("int gpio_read(int target)"),
                    MForwardDecl("int gpio_write(int target, int val)"));
                }
                public MCode CaseUSER_DEFINED(USER_DEFINED term) {
                    return MCode(Strings(start, "",
                        "// user-defined callback code"
                    ).addAll(term.content()).addAll(end));
                }
        });
        return MProcedure(MDocumentation(Strings(
                "The Xil_ExceptionHandler to be used as callback for the " + gpio.name() + " component.",
                "This handler executes user-defined code and clears the interrupt."
            )), MModifiers(PRIVATE()),
            MVoid(), "GpioHandler_" + gpio.name(), MParameters(
                MParameter(VALUE(), MPointerType(MType("void")), "CallbackRef")
            ), body);
    }

    private MCode initGPI(GPIO gpio) throws ParserError {
        String name = gpio.name();
        return MCode(Strings(
            "loopy_print(\"\\ninitialise " + name + " ...\");",
            "status = XGpio_Initialize(&gpi_components[gpi_" + name + "], " + deviceID(gpio) + ");",
            "if(status != XST_SUCCESS) {",
            "    loopy_print(\" error setting up " + name + ": %d\", status);",
            "    return;",
            "}",
            "XGpio_SetDataDirection(&gpi_components[gpi_" + name + "], GPIO_CHANNEL1, 0x0);",
            "status = GpioIntrSetup(&gpi_components[gpi_" + name + "], " + deviceID(gpio) + ",",
            "    " + deviceIntrChannel(gpio) + ", GPIO_CHANNEL1, GpioHandler_" + name + ");",
            "if(status != XST_SUCCESS) {",
            "    loopy_print(\" error setting up " + name + ": %d\", status);",
            "    return;",
            "}",
            "loopy_print(\" done\");",
            ""
        ), MQuoteInclude("gpio.h"), MQuoteInclude("xparameters.h"));
    }

    private MCode initGPO(GPIO gpio) throws ParserError {
        String name = gpio.name();
        return MCode(Strings(
            "loopy_print(\"\\ninitialise " + name + " ...\");",
            "status = XGpio_Initialize(&gpo_components[gpo_" + name + "], " + deviceID(gpio) + ");",
            "if(status != XST_SUCCESS) {",
            "    loopy_print(\" error setting up " + name + ": %d\", status);",
            "    return;",
            "}",
            "XGpio_SetDataDirection(&gpo_components[gpo_" + name + "], GPIO_CHANNEL1, 0x0);",
            "loopy_print(\" done\");",
            ""
        ), MQuoteInclude("gpio.h"), MQuoteInclude("xparameters.h"));
    }

    private String deviceID(GPIO gpio) throws ParserError {
        if(gpio.name().equals("leds"))          return "XPAR_LEDS_8BITS_DEVICE_ID";
        else if(gpio.name().equals("switches")) return "XPAR_DIP_SWITCHES_8BITS_DEVICE_ID";
        else if(gpio.name().equals("buttons"))  return "XPAR_PUSH_BUTTONS_5BITS_DEVICE_ID";

        throw new ParserError("Unknown GPIO device " + gpio.name() + " for Virtex6",
            gpio.pos().filename(), gpio.pos().line());
    }

    private String deviceIntrChannel(GPIO gpio) throws ParserError{
        if(gpio.name().equals("switches"))     return "XPAR_MICROBLAZE_0_INTC_DIP_SWITCHES_8BITS_IP2INTC_IRPT_INTR";
        else if(gpio.name().equals("buttons")) return "XPAR_MICROBLAZE_0_INTC_PUSH_BUTTONS_5BITS_IP2INTC_IRPT_INTR";

        throw new ParserError("Unknown GPIO device " + gpio.name() + " for Virtex6",
            gpio.pos().filename(), gpio.pos().line());
    }

    private String hwInstance(GPIO gpio) throws ParserError {
        if(gpio.name().equals("leds"))          return "leds_8bits";
        else if(gpio.name().equals("switches")) return "dip_switches_8bits";
        else if(gpio.name().equals("buttons"))  return "push_buttons_5bits";

        throw new ParserError("Unknown GPIO device " + gpio.name() + " for Virtex6",
            gpio.pos().filename(), gpio.pos().line());
    }

    private String hwVersion(GPIO gpio) throws ParserError {
        if(gpio.name().equals("leds"))          return version_gpio_leds;
        else if(gpio.name().equals("switches")) return version_gpio_switches;
        else if(gpio.name().equals("buttons"))  return version_gpio_buttons;

        throw new ParserError("Unknown GPIO device " + gpio.name() + " for Virtex6",
            gpio.pos().filename(), gpio.pos().line());
    }

    public void visit(final CPUAxisPos axis) {
        AXIPos port = getPort(axis);

        int width = getWidth(axis);
        boolean d = port.direction() instanceof INPos;
//        boolean up = ((width < 32) && !d) || ((width > 32) && d);

        String axisGroup   = d ? "m" + axiStreamIdMaster : "s" + axiStreamIdSlave;

        if(getHWQueueSize(axis) > 0) mssFile = addBlock(mssFile, MHS.Block("DRIVER",
            MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("DRIVER_NAME", MHS.Ident("generic"))),
            MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("DRIVER_VER", MHS.Ident(version_queue))),
            MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("HW_INSTANCE", MHS.Ident(axisGroup + "_queue")))
        ));

        if(width < 32) mssFile = addBlock(mssFile, MHS.Block("DRIVER",
            MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("DRIVER_NAME", MHS.Ident("generic"))),
            MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("DRIVER_VER", MHS.Ident(version_resizer))),
            MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("HW_INSTANCE", MHS.Ident(axisGroup + "_mux")))
        ));
        else if(width > 32) mssFile = addBlock(mssFile, MHS.Block("DRIVER",
            MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("DRIVER_NAME", MHS.Ident("generic"))),
            MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("DRIVER_VER", MHS.Ident(version_resizer))),
            MHS.Attribute(MHS.PARAMETER(), MHS.Assignment("HW_INSTANCE", MHS.Ident(axisGroup + "_mux")))
        ));

        port.direction().termDirection().Switch(new Direction.Switch<Boolean, NE>() {
            public Boolean CaseIN(IN term) {
                addWriteStream(axis); return null;
            }
            public Boolean CaseOUT(OUT term) {
                addReadStream(axis); return null;
            }
            public Boolean CaseDUAL(DUAL term) {
                addWriteStream(axis); addReadStream(axis); return null;
            }
        });
    }

    private void addWriteStream(CPUAxisPos axis) {
        if(axiStreamIdMaster>15) {
            errors.addError(new ParserError("too many writing AXI stream interfaces for Virtex6", "", -1));
            return;
        }
        axi_write = addLines(axi_write, MCode(Strings(
            "case "+(axiStreamIdMaster>9?"":" ")+axiStreamIdMaster+
            ": putfslx(val, "+(axiStreamIdMaster>9?"":" ")+axiStreamIdMaster+", FSL_NONBLOCKING); break;"
        )));

        init = addLines(init, MCode(
            Strings("inQueue[" + axiStreamIdMaster + "] = createQueue(" + getSWQueueSize32(axis) + ");"),
            MQuoteInclude("../io.h")
        ));
        axiStreamIdMaster++;
    }

    private void addReadStream(CPUAxisPos axis) {
        if(axiStreamIdSlave>15) {
            errors.addError(new ParserError("too many reading AXI stream interfaces for Virtex6", "", -1));
            return;
        }
        axi_read  = addLines(axi_read,  MCode(Strings(
            "case "+(axiStreamIdSlave>9?"":" ")+axiStreamIdSlave+
            ": getfslx(*val, "+(axiStreamIdSlave>9?"":" ")+axiStreamIdSlave+", FSL_NONBLOCKING); break;"
        )));

        init = addLines(init, MCode(
            Strings("outQueueCap[" + axiStreamIdSlave + "] = " + getSWQueueSize32(axis) + ";"),
            MQuoteInclude("../io.h")
        ));

        init = addLines(init, MCode(
            Strings("isPolling[" + axiStreamIdSlave + "] = " + (isPolling(axis) ? 1 : 0) + ";"),
            MQuoteInclude("../io.h")
        ));

        init = addLines(init, MCode(
            Strings("pollCount[" + axiStreamIdSlave + "] = " + getPollingCount32(axis) + ";"),
            MQuoteInclude("../io.h")
        ));

        axiStreamIdSlave++;
    }

    // list types
    public void visit(GPIOsPos     term) { for(    GPIOPos gpio : term) visit(gpio); }
    public void visit(InstancesPos term) { for(InstancePos inst : term) visit(inst); }
    public void visit(BindingsPos  term) { for( BindingPos bind : term) visit(bind); }
    public void visit(MOptionsPos  term) { for( MOptionPos opt  : term) visit(opt);  }

    // general (handled before this visitor)
    public void visit(ImportPos term)  { }
    public void visit(BackendPos term) { }

    // scheduler (irrelevant for host-side driver
    public void visit(SchedulerPos term) {
        if(term.code() instanceof DEFAULTPos) scheduler = MFile(MDocumentation(Strings(
                "A primitive scheduler.",
                "Reads values from the medium, shifts values between Microblaze and VHDL components,",
                "and writes results back to the medium."
            ), AUTHOR("Thomas Fischer"), SINCE("18.02.2013")
            ), "scheduler", targetSrc.getPath(), MPreProcDirs(), MStructs(), MEnums(), MAttributes(), MProcedures(
                MProcedure(
                    MDocumentation(Strings(
                        "Starts the scheduling loop.",
                        "The scheduling loop performs the following actions in each iteration:",
                        " - read and process messages from the medium",
                        " - write values from Microblaze input queue to hardware input queue for each input stream",
                        " - write values from hardware output queue to the medium (caches several values before sending)"
                    )), MModifiers(), MVoid(), "schedule", MParameters(), defaultScheduler()
                )
            )
         );
        else scheduler = MFile(MDocumentation(Strings()
            ), "scheduler", targetSrc.getPath(), MPreProcDirs(), MStructs(), MEnums(), MAttributes(), MProcedures(
                MProcedure(
                    MDocumentation(Strings()), MModifiers(), MVoid(), "schedule", MParameters(), MCode(
                        Strings().addAll(((USER_DEFINED)term.code().term()).content()),
                        MQuoteInclude("constants.h"),
                        MQuoteInclude("queueUntyped.h"),
                        MQuoteInclude("io.h"),
                        MForwardDecl("void medium_read()"),
                        MForwardDecl("int axi_write ( int val, int target )"),
                        MForwardDecl("int axi_read ( int *val, int target )")
                    )
                )
            )
        );
    }

    private MCode defaultScheduler() {
        return MCode(
            Strings(
                "unsigned int pid;",
                "unsigned int i;",
                "",
                "while(1) {",
                "    // receive a package from the interface (or all?)",
                "    // esp stores data packages in sw queue",
                "    medium_read();",
                "    ",
                "    // write data from sw queue to hw queue (if possible)",
                "    for(pid = 0; pid < IN_STREAM_COUNT; pid++) {",
                "        for(i = 0; i < inQueue[pid]->cap; i++) {",
                "            // go to next port if the sw queue is empty",
                "            if(inQueue[pid]->size == 0) break;",
                "            ",
                "            // try to write, skip if the hw queue is full",
                "            if(axi_write(peek(inQueue[pid]), pid)) {",
                "                #if DEBUG",
                "                  loopy_print(\"\\nfailed to write to AXI stream\");",
                "                #endif /* DEBUG */",
                "                break;",
                "            }",
                "            ",
                "            // remove the read value from the queue",
                "            take(inQueue[pid]);",
                "            ",
                "            // if the queue was full beforehand, poll",
                "            if(inQueue[pid]->size == inQueue[pid]->cap - 1) send_poll(pid);",
                "        }",
                "    }",
                "    ",
                "    // read data from hw queue (if available) and cache in sw queue",
                "    // flush sw queue, if it's full or the hw queue is empty",
                "    for(pid = 0; pid < OUT_STREAM_COUNT; pid++) {",
                "        for(i = 0; i < outQueueCap[pid] && ((!isPolling[pid]) || pollCount[pid] > 0); i++) {",
//                "            int val = 0;",
//                "            ",
                "            // try to read, break if if fails",
                "            if(axi_read(&outQueue[outQueueSize], pid)) break;",
                "            ",
                "            // otherwise increment the queue size counter",
//                "            outQueue[outQueueSize] = val;",
                "            outQueueSize++;",
                "            ",
                "            // decrement the poll counter (if the port was polling)",
                "            if(isPolling[pid]) pollCount[pid]--;",
                "        }",
                "        // flush sw queue",
                "        flush_queue(pid);",
                "        outQueueSize = 0;",
                "    }",
                "}"
            ),
            MQuoteInclude("constants.h"),
            MQuoteInclude("queueUntyped.h"),
            MQuoteInclude("io.h"),
            MForwardDecl("void medium_read()"),
            MForwardDecl("int axi_write ( int val, int target )"),
            MForwardDecl("int axi_read ( int *val, int target )")
        );
    }

    // code blocks (handled directly when occurring)
    public void visit(DEFAULTPos term)      { }
    public void visit(USER_DEFINEDPos term) { }

    // missing medium declaration
    public void visit(NONEPos term) { }

    // options (handled directly inside the board or port if occurring)
    public void visit(HWQUEUEPos  arg0) { }
    public void visit(SWQUEUEPos  arg0) { }
    public void visit(BITWIDTHPos term) { }
    public void visit(DEBUGPos    term) { }
    public void visit(POLLPos     term) { }

    // same goes for medium options
    public void visit(MACPos    term) { }
    public void visit(IPPos     term) { }
    public void visit(MASKPos   term) { }
    public void visit(GATEPos   term) { }
    public void visit(PORTIDPos term) { }

    // cores
    // we do not need to visit cores here, since a class will be created
    // for each instance directly connected to the boards CPU, not for each core
    public void visit(CoresPos term) { }
    public void visit(CorePos  term) { }

    // ports (see above)
    public void visit(PortsPos term) { }
    public void visit(CLKPos   term) { }
    public void visit(RSTPos   term) { }
    public void visit(AXIPos   term) { }
    public void visit(INPos    term) { }
    public void visit(OUTPos   term) { }
    public void visit(DUALPos  term) { }

    // ignore instances here, just visit port bindings
    public void visit(InstancePos term) { visit(term.bind()); }
    // component axis (these get ignored... that's the whole point)
    public void visit(AxisPos     term) { }

    // position
    public void visit(PositionPos term) { }

    // literals
    public void visit(StringsPos term) { }
    public void visit(StringPos  term) { }
    public void visit(IntegerPos term) { }
    public void visit(BooleanPos term) { }

    private void addIP(String id, String ipString, String doc) {
        String[] ip = ipString.split("\\.");
        addConst(id + "_1", ip[0], "The first  8 bits of the " + doc + " of this board.");
        addConst(id + "_2", ip[1], "The second 8 bits of the " + doc + " of this board.");
        addConst(id + "_3", ip[2], "The third  8 bits of the " + doc + " of this board.");
        addConst(id + "_4", ip[3], "The fourth 8 bits of the " + doc + " of this board.");
    }

    private void addMAC(String macString) {
        String[] mac = macString.split(":");
        addConst("MAC_1", "0x" + mac[0], "The first  8 bits of the MAC address of this board.");
        addConst("MAC_2", "0x" + mac[1], "The second 8 bits of the MAC address of this board.");
        addConst("MAC_3", "0x" + mac[2], "The third  8 bits of the MAC address of this board.");
        addConst("MAC_4", "0x" + mac[3], "The fourth 8 bits of the MAC address of this board.");
        addConst("MAC_5", "0x" + mac[4], "The fifth  8 bits of the MAC address of this board.");
        addConst("MAC_6", "0x" + mac[5], "The sixth  8 bits of the MAC address of this board.");
    }

    private void addConst(String id, String val, String doc, MInclude ... needed) {
        constants = add(constants, MDef(MDocumentation(Strings(doc)), MModifiers(PUBLIC()), id, val, needed));
    }

    private MHSFile addBlock(MHSFile file, Block block) {
        return file.replaceBlocks(file.blocks().add(block));
    }
}
