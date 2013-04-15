package de.hopp.generator.backends.client.cpp;

import static de.hopp.generator.backends.BackendUtils.defaultQueueSizeHW;
import static de.hopp.generator.backends.BackendUtils.defaultQueueSizeSW;
import static de.hopp.generator.model.Model.*;
import static de.hopp.generator.utils.BoardUtils.getPort;
import static de.hopp.generator.utils.Model.add;
import static de.hopp.generator.utils.Model.addDoc;
import static de.hopp.generator.utils.Model.addInit;
import static de.hopp.generator.utils.Model.addParam;

import java.io.File;

import katja.common.NE;
import de.hopp.generator.Configuration;
import de.hopp.generator.frontend.*;
import de.hopp.generator.frontend.BDLFilePos.Visitor;
import de.hopp.generator.model.MClass;
import de.hopp.generator.model.MConstr;
import de.hopp.generator.model.MDestr;
import de.hopp.generator.model.MFile;
import de.hopp.generator.model.MInitList;
import de.hopp.generator.model.Strings;

public class CPPBDLVisitor extends Visitor<NE> {

    // generated files
    MFile comps;
    MFile consts;
    
    // temp variables for construction of local methods of VHDL components
    private MClass  comp;
    private MConstr constructor;
    private MDestr  destructor;
    
    // MOAR integers...
    private int  pi = 0,  po = 0;
    private int gpi = 0, gpo = 0;
    
  // local variables for global default methods
//  private MMethod clean;
    
    public CPPBDLVisitor(Configuration config) {
        String clientSrc = new File(config.clientDir(), "src").getPath();
        String clientApi = new File(clientSrc, "api").getPath();
        
        comps = MFile(MDocumentation(Strings()), "components", clientApi, MDefinitions(),
                MStructs(), MEnums(), MAttributes(), MProcedures(), MClasses());
        consts = MFile(MDocumentation(Strings()), "constants", clientSrc, MDefinitions(),
                MStructs(), MEnums(), MAttributes(), MProcedures(), MClasses());
        
        comps  = addDoc(comps,  "Describes user-defined IPCores and instantiates all cores present within this driver.");
        consts = addDoc(consts, "Defines several constants used by the client.");
    }
    
    @Override
    public void visit(BDLFilePos term) {
        
        boolean debug = false;
        int queueSizeSW = defaultQueueSizeSW, queueSizeHW = defaultQueueSizeHW;

        for(Option o : term.opts().term()) {
            // duplicates and invalid parameters are already caught by sanity check
            if(o instanceof HWQUEUE) queueSizeHW = ((HWQUEUE)o).qsize();
            if(o instanceof SWQUEUE) queueSizeSW = ((SWQUEUE)o).qsize();
            if(o instanceof DEBUG)   debug = true;
        }

        // add derived constants 
        consts = add(consts, MDefinition(MDocumentation(Strings(
                "If set, enables additional console output for debugging purposes"
            )), MModifiers(PUBLIC()), "DEBUG", debug ? "1" : "0"));
        consts = add(consts, MDefinition(MDocumentation(Strings(
                "Defines the default size of the boards hardware queues."
            )), MModifiers(PUBLIC()), "QUEUE_SIZE_HW", String.valueOf(queueSizeHW)));
        consts = add(consts, MDefinition(MDocumentation(Strings(
                "Defines the default size of the boards software queues.",
                "This is equivalent with the maximal number of values, " +
                "that should be send in one message"
            )), MModifiers(PUBLIC()), "QUEUE_SIZE_SW", String.valueOf(queueSizeSW)));
        
        visit(term.medium());
        visit(term.gpios());
        visit(term.scheduler());
        visit(term.cores());
        visit(term.insts());
        
        consts = add(consts, MDefinition(MDocumentation(Strings(
                "The number of in-going component ports"
            )), MModifiers(PUBLIC()),  "IN_PORT_COUNT", String.valueOf(pi)));
        consts = add(consts, MDefinition(MDocumentation(Strings(
                "The number of out-going component ports"
            )), MModifiers(PUBLIC()), "OUT_PORT_COUNT", String.valueOf(po)));
        consts = add(consts, MDefinition(MDocumentation(Strings(
                "The number of gpi components"
            )), MModifiers(PUBLIC()), "GPI_COUNT", String.valueOf(gpi)));
        consts = add(consts, MDefinition(MDocumentation(Strings(
                "The number of gpo components"
            )), MModifiers(PUBLIC()), "GPO_COUNT", String.valueOf(gpo)));
    }

    // We assume all imports to be accumulated at the parser
    public void visit(ImportsPos term)   { }
    public void visit(BackendsPos term)  { }
    public void visit(OptionsPos term) { }

    @Override
    public void visit(ETHERNETPos term) {
//          comps = add(comps, MAttribute(MDocumentation(Strings()), MModifiers(PRIVATE()),
//          MPointerType(MType("interface")), "intrfc",
//          MCodeFragment("new ethernet(\"192.168.1.10\", 8844)", MQuoteInclude("interface.h"))));
    }

    public void visit(UARTPos term) { }
    
    public void visit(PCIEPos term) { }
    
    @Override
    public void visit(GPIOPos term) {
        // construct init block according to GPIO direction
        MInitList init = MInitList(Strings(), MIncludes(MQuoteInclude("gpio.h")));
        
//        switch(term.name().term()) {
//        case "led": init.replaceParams(Strings(String.valueOf(gpo++))); break;
//        case "buttons": init.replaceParams(Strings(String.valueOf(gpi++))); break;
//        case "switch": init.replaceParams(Strings(String.valueOf(gpi++))); break;
//        default: errors.addError(new UsageError("abc"));
//        }
        init = init.replaceParams(term.direction().Switch(new DirectionPos.Switch<Strings, NE>() {
            public Strings CaseINPos(INPos term) {
                return Strings(String.valueOf(gpi++));
            }
            public Strings CaseOUTPos(OUTPos term) {
                return Strings(String.valueOf(gpo++));
            }
            public Strings CaseDUALPos(DUALPos term) {
                return Strings(String.valueOf(gpi++), String.valueOf(gpo++));
            }
        }));

        // TODO generate gpio class into gpio file (currently treated as generic, containing all known devices)
        
        // add attribute for the GPIO component
        comps = add(comps, MAttribute(MDocumentation(Strings(
                "An instance of the #" + term.name().term() + " core."
            )), MModifiers(PUBLIC()), MType("class " + term.name().term()), "gpio_"+term.name().term(), init));
        term.name();
        term.callback();
    }

    @Override
    public void visit(InstancePos term) {
        if(!hasCPUConnection(term)) return;
        
        comp = MClass(MDocumentation(Strings(
                "An abstract representation of the #" + term.name().term() + " core."
            ), SEE("components.h for a list of core instances within this board driver.")
            ), MModifiers(), term.name().term(), MExtends(MExtend(PRIVATE(), MType("component"))),
            MStructs(), MEnums(), MAttributes(), MMethods());

        constructor = MConstr(MDocumentation(Strings(
                "Constructor for the #" + term.name().term() + " core.",
                "Creates a new " + term.name().term() + " instance on a board attached to the provided communication medium."
            )), MModifiers(PUBLIC()), MParameters(), MMemberInits(), MCode(Strings(), MQuoteInclude("component.h")));
        destructor  = MDestr(MDocumentation(Strings(
                "Destructor for the #" + term.name().term() + " core.",
                "Deletes registered ports and unregisters the core from the communication medium."
            )), MModifiers(PUBLIC()), MParameters(), MCode(Strings()));

        MInitList init = MInitList(Strings());
        // add ports
        for(BindingPos bind : term.bind()) {
            if(isMasterConnection(bind)) init = add(init, String.valueOf(pi++));
            if(isSlaveConnection(bind))  init = add(init, String.valueOf(po++));
        }
        
        // visit bindings to add ports to component
        visit(term.bind());
        
        // compose class and add to file
        comp  = add(comp,  constructor);
        comp  = add(comp,  destructor);
        comps = add(comps, comp);

        // add attribute to component file
        comps = add(comps, MAttribute(MDocumentation(Strings(
                "An instance of the #" + term.name().term() + " core."
            )), MModifiers(PUBLIC()), MType("class " + term.name().term()), term.name().term(), init));
    }

    public void visit(final CPUAxisPos axis) {
        PortPos port = getPort(axis.root(), ((InstancePos)axis.parent().parent()).core().term(), axis.port().term());
        port.direction().Switch(new DirectionPos.Switch<Object, NE>() {
            public Object CaseDUALPos(DUALPos term) {
                addPort(axis.port().term(), "dual", "A bi-directional AXI-Stream port.", false);
                return null;
            }
            public Object CaseOUTPos(OUTPos term) {
                addPort(axis.port().term(), "out", "An out-going AXI-Stream port.", true);
                return null;
            }
            public Object CaseINPos(INPos term) {
                addPort(axis.port().term(), "in", "An in-going AXI-Stream port.", true);
                return null;
            }
        });
    }
    
    private boolean hasCPUConnection(InstancePos term) {
        for(BindingPos bind : term.bind()) if(isCPUConnection(bind)) return true;
        return false;
    }

    private boolean isCPUConnection(BindingPos term) {
        if(term instanceof CPUAxisPos) return true;
        return false;
    }
    
    private static boolean isMasterConnection(BindingPos term) {
        PortPos port = getPort(term.root(), ((InstancePos)term.parent().parent()).core().term(), term.port().term());
        return port.direction().Switch(new DirectionPos.Switch<Boolean, NE>() {
            public Boolean CaseDUALPos(DUALPos term) { return true;  }
            public Boolean CaseOUTPos(OUTPos term)   { return false; }
            public Boolean CaseINPos(INPos term)     { return true;  }
        });
    }
    private static boolean isSlaveConnection(BindingPos term) {
        PortPos port = getPort(term.root(), ((InstancePos)term.parent().parent()).core().term(), term.port().term());
        return port.direction().Switch(new DirectionPos.Switch<Boolean, NE>() {
            public Boolean CaseDUALPos(DUALPos term) { return true;  }
            public Boolean CaseOUTPos(OUTPos term)   { return true; }
            public Boolean CaseINPos(INPos term)     { return false;  }
        });
    }
    
    private void addPort(String name, String type, String docPart, boolean single) {
        comp = add(comp, MAttribute(MDocumentation(Strings(
                    docPart, "Communicate with the #" + comp.name() + " core through this port."
                )), MModifiers(PUBLIC()), MType(type),
                name, MCodeFragment("", MQuoteInclude("component.h"), MQuoteInclude("port.h"))));
        
        if(single) {
            constructor = constructor.replaceDoc(constructor.doc().replaceTags(constructor.doc().tags().add(
                        PARAM(name, "Id of the port")
                    )));
            constructor = addParam(constructor, MParameter(VALUE(), MType("unsigned char"), name));
            constructor = addInit(constructor, MMemberInit(name, name));
        } else {
            constructor = constructor.replaceDoc(constructor.doc().replaceTags(constructor.doc().tags().addAll(MTags(
                    PARAM(name + "_in",  "Id of the in-going part of the port"),
                    PARAM(name + "_out", "Id of the out-going part of the port")
                ))));
            constructor = addParam(constructor, MParameter(VALUE(), MType("unsigned char"), name + "_in"));
            constructor = addParam(constructor, MParameter(VALUE(), MType("unsigned char"), name + "_out"));
            constructor = addInit(constructor, MMemberInit(name, name + "_in", name + "_out"));
        }
    }
    
    // list types
    public void visit(GPIOsPos     term) { for(    GPIOPos gpio : term) visit(gpio); }
    public void visit(InstancesPos term) { for(InstancePos inst : term) visit(inst); }
    public void visit(BindingsPos  term) { for( BindingPos bind : term) visit(bind); }
    public void visit(MOptionsPos  term) { for( MOptionPos  opt : term) visit( opt); }
    
    // general (handled before this visitor)
    public void visit(ImportPos term)  { }
    public void visit(BackendPos term) { }

    // scheduler (irrelevant for host-side driver
    public void visit(SchedulerPos term) { }
    
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
    public void visit(PortPos  term) { }
    public void visit(INPos    term) { }
    public void visit(OUTPos   term) { }
    public void visit(DUALPos  term) { }
    
    // component axis (these get ignored... that's the whole point)
    public void visit(AxisPos term) { }
    
    // positions
    public void visit(PositionPos term) { }
    
    // literals
    public void visit(StringsPos term) { }
    public void visit(StringPos  term) { }
    public void visit(IntegerPos term) { }

    
}