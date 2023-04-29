package com.lushprojects.circuitjs1.client;

import com.google.gwt.user.client.ui.Button;
import com.google.gwt.core.client.JavaScriptObject;
import com.google.gwt.core.client.JsArrayBoolean;
import com.google.gwt.core.client.JsArrayInteger;
import com.google.gwt.core.client.JsArrayString;
import com.lushprojects.circuitjs1.client.util.Locale;

public class JavaScriptElm extends ChipElm {
    String modelName;
    int postCount;
    int inputCount, outputCount;
    JavaScriptObject implementation;
    boolean lastValues[];
    boolean highImpedance[];
    static String lastModelName = "default";
    int javascriptErrors = 0;
    
    public JavaScriptElm(int xx, int yy) {
        super(xx, yy);
        modelName = lastModelName;
        setupPins();
    }

    public JavaScriptElm(int xa, int ya, int xb, int yb, int f,
            StringTokenizer st) {
        super(xa, ya, xb, yb, f, st);
        modelName = CustomLogicModel.unescape(st.nextToken());
        updateModels();
        int i;
        for (i = 0; i != getPostCount(); i++) {
            if (pins[i].output) {
                volts[i] = new Double(st.nextToken()).doubleValue();
                pins[i].value = volts[i] > getThreshold();
            }
        }
    }
    
    String dump() {
        String s = super.dump();
        s += " " + CustomLogicModel.escape(modelName);

        // the code to do this in ChipElm doesn't work here because we don't know
        // how many pins to read until we read the model name!  So we have to
        // duplicate it here.
        int i;
        for (i = 0; i != getPostCount(); i++) {
            if (pins[i].output)
                s += " " + volts[i];
        }
        return s;
    }
    
    public void updateModels() {
        setupPins();
        allocNodes();
        setPoints();
    }

    private native JavaScriptObject getImplementationFromJavaScript(String name, JavaScriptObject old) /*-{
        var cls = $wnd[name];
        if (!cls)
            return null;
        if (old && cls.upgrade)
            return cls.upgrade(old);
        else
            return new cls();
    }-*/;

    //FIXME use JsArrayString
    private native int getInputCount(JavaScriptObject implementation) /*-{ return implementation.inputs.length; }-*/;
    private native String getInputName(JavaScriptObject implementation, int i) /*-{ return implementation.inputs[i]; }-*/;
    private native int getOutputCount(JavaScriptObject implementation) /*-{ return implementation.outputs.length; }-*/;
    private native String getOutputName(JavaScriptObject implementation, int i) /*-{ return implementation.outputs[i]; }-*/;
    private native boolean getNeedsTristate(JavaScriptObject implementation) /*-{
        return implementation.needsTristate === true;
    }-*/;
    private native String getInfoText(JavaScriptObject implementation, String defaultText) /*-{
        return implementation && implementation.infoText || defaultText;
    }-*/;

    @Override
    void setupPins() {
        if (modelName == null) {
            // We are called from the constructor of ChipElm. Do hardly anything, yet.
            implementation = null;
            postCount = bits;
            allocNodes();
            return;
        }

        console("setupPins for " + modelName);
        implementation = getImplementationFromJavaScript(modelName, implementation);
        consoleLogObject(implementation);
        javascriptErrors = 0;

        if (implementation == null) {
            // We must add some pins so the element can be drawn.
            inputCount = 1;
            outputCount = 0;
            sizeY = 1;
            sizeX = 2;
            postCount = 1;
            pins = new Pin[1];
            pins[0] = new Pin(0, SIDE_W, "JavascriptNotAvailable");
            return;
        }

        try {
            console("init inputs and outputs");
            inputCount = getInputCount(implementation);
            outputCount = getOutputCount(implementation);
            sizeY = inputCount > outputCount ? inputCount : outputCount;
            if (sizeY == 0)
                sizeY = 1;
            sizeX = 2;
            postCount = inputCount+outputCount;
            pins = new Pin[postCount];
            int i;
            for (i = 0; i != inputCount; i++) {
                pins[i] = new Pin(i, SIDE_W, getInputName(implementation, i));
                pins[i].fixName();
            }
            for (i = 0; i != outputCount; i++) {
                pins[i+inputCount] = new Pin(i, SIDE_E, getOutputName(implementation, i));
                pins[i+inputCount].output = true;
                pins[i+inputCount].fixName();
            }
            lastValues = new boolean[postCount];
            highImpedance = new boolean[postCount];
            console("init inputs and outputs: done");
        } catch (Exception e) {
            console("init inputs and outputs: error: " + e);
            e.printStackTrace();
        }
    }

    int getPostCount() { return postCount; }
    
    @Override
    int getVoltageSourceCount() {
        return outputCount;
    }

    // keep track of whether we have any tri-state outputs.  if not, then we can simplify things quite a bit, making the simulation faster
    boolean hasTriState() { return implementation == null ? false : getNeedsTristate(implementation); }
    
    boolean nonLinear() { return hasTriState(); }
    
    int getInternalNodeCount() {
        // for tri-state outputs, we need an internal node to connect a voltage source to, and then connect a resistor from there to the output.
        // we do this for all outputs if any of them are tri-state
        return (hasTriState()) ? outputCount : 0; 
    }
    
    void stamp() {
        int i;
        int add = (hasTriState()) ? outputCount : 0;
        for (i = 0; i != getPostCount(); i++) {
            Pin p = pins[i];
            if (p.output) {
                sim.stampVoltageSource(0, nodes[i+add], p.voltSource);
                if (hasTriState()) {
                    sim.stampNonLinear(nodes[i+add]);
                    sim.stampNonLinear(nodes[i]);
                }
            }
        }
    }
    
    void doStep() {
        int i;
        for (i = 0; i != getPostCount(); i++) {
            Pin p = pins[i];
            if (!p.output)
                p.value = volts[i] > getThreshold();
        }
        execute();
        int add = (hasTriState()) ? outputCount : 0;
        for (i = 0; i != getPostCount(); i++) {
            Pin p = pins[i];
            if (p.output) {
                // connect output voltage source (to internal node if tri-state, otherwise connect directly to output)
                sim.updateVoltageSource(0, nodes[i+add], p.voltSource, p.value ? highVoltage : 0);
                
                // add resistor for tri-state if necessary
                if (hasTriState())
                    sim.stampResistor(nodes[i+add], nodes[i], highImpedance[i] ? 1e8 : 1e-3);
            }
        }
    }

    public static native void console(String text)
    /*-{
	    console.log(text);
	}-*/;
    public static native void consoleLogObject(JavaScriptObject obj)
    /*-{
	    console.log(obj);
	}-*/;
    private native JsArrayBoolean makeBoolArray() /*-{ return []; }-*/;
    private native JsArrayInteger executeStep(JavaScriptObject implementation, JavaScriptObject inputs, double time) /*-{
        try {
            var outputs = implementation.execute(inputs, time);
            return outputs.map(function (x) {
                if (x == 0 || x === false)
                    return false;
                else if (x == 1 || x === true)
                    return true;
                else if (x == -1)
                    return -1;
                else {
                    console.log("Unexpected value in return of Javascript implementation (executeStep) of circuit element:", x, implementation, inputs, outputs);
                    return -1;
                }
            });
        } catch (e) {
            console.log("Exception in Javascript implementation of circuit element:", e, implementation, inputs);
            return null;
        }
    }-*/;
    private native boolean startIteration(JavaScriptObject implementation, double time) /*-{
        try {
            if (implementation.startIteration)
                implementation.startIteration(time);
            return true;
        } catch (e) {
            console.log("Exception in Javascript implementation (startIteration) of circuit element:", e, implementation, inputs);
            return false;
        }
    }-*/;
    private native boolean stepFinished(JavaScriptObject implementation, double time) /*-{
        try {
            if (implementation.stepFinished)
                implementation.stepFinished(time);
            return true;
        } catch (e) {
            console.log("Exception in Javascript implementation (stepFinished) of circuit element:", e, implementation, inputs);
            return false;
        }
    }-*/;

    void startIteration() {
        if (implementation == null || javascriptErrors > 10)
            return;
        boolean ok = startIteration(implementation, sim.t);
        if (!ok)
            javascriptErrors++;
    }
	void stepFinished() {
        if (implementation == null || javascriptErrors > 10)
            return;
        boolean ok = stepFinished(implementation, sim.t);
        if (!ok)
            javascriptErrors++;
    }

    void execute() {
        if (implementation == null || javascriptErrors > 10)
            return;

        int i;
        JsArrayBoolean inputValues = makeBoolArray();
        for (i = 0; i < inputCount; i++) {
            inputValues.push(pins[i].value);
        }

        JsArrayInteger outputValues = executeStep(implementation, inputValues, sim.t);
        if (outputValues == null) {
            javascriptErrors++;
            return;
        }
        for (i = 0; i != outputValues.length() && i != outputCount; i++) {
            highImpedance[i+inputCount] = (outputValues.get(i) < 0);
            pins[i+inputCount].value = (outputValues.get(i) == 1);
        }
        
        // save values for transition checking
        int j;
        for (j = 0; j != postCount; j++)
            lastValues[j] = pins[j].value;
    }
    
    public EditInfo getChipEditInfo(int n) {
        if (n == 0) {
            EditInfo ei = new EditInfo("JavaScript Class", 0, -1, -1);
            ei.text = modelName;
            ei.disallowSliders();
            ei.newDialog = true;  // update edit dialog if model name is changed
            return ei;
        }
        return null;
    }
    
    public void setChipEditValue(int n, EditInfo ei) {
        if (n == 0) {
            String newModelName = ei.textf.getText();
            if (modelName.equals(newModelName))
                return;
            modelName = lastModelName = ei.textf.getText();
            updateModels();
            return;
        }
    }
    
    int getDumpType() { return 600; }

    void getInfo(String arr[]) {
        super.getInfo(arr);
        arr[0] = getInfoText(implementation, modelName);
    }
}
