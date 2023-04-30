package com.lushprojects.circuitjs1.client;

import com.google.gwt.user.client.ui.Button;
import com.google.gwt.core.client.JavaScriptObject;
import com.google.gwt.core.client.JsArrayBoolean;
import com.google.gwt.core.client.JsArrayInteger;
import com.google.gwt.core.client.JsArrayString;
import com.google.gwt.core.client.JavaScriptException;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.TextBox;
import com.lushprojects.circuitjs1.client.util.Locale;

public class JavaScriptElm extends ChipElm {
    String modelName, modelStateString;
    int postCount;
    int inputCount, outputCount;
    boolean lastValues[];
    boolean highImpedance[];
    static String lastModelName = "default";

    JavaScriptObject implementation = null;
    int javascriptErrors = 0;
    boolean implementationValid = false;
    String javascriptError = null;
    
    public JavaScriptElm(int xx, int yy) {
        super(xx, yy);
        modelName = lastModelName;
        modelStateString = "null";
        setupPins();
    }

    public JavaScriptElm(int xa, int ya, int xb, int yb, int f,
            StringTokenizer st) {
        super(xa, ya, xb, yb, f, st);
        modelName = CustomLogicModel.unescape(st.nextToken());
        modelStateString = st.hasMoreTokens() ? CustomLogicModel.unescape(st.nextToken()) : "null";
        updateModels();
        int i;
        for (i = 0; i != getPostCount(); i++) {
            if (pins[i].output) {
                pins[i].value = false;
            }
        }
    }
    
    String dump() {
        String s = super.dump();
        s += " " + CustomLogicModel.escape(modelName);
        s += " " + CustomLogicModel.escape(getStateString());
        return s;
    }
    
    public void updateModels() {
        setupPins();
        allocNodes();
        setPoints();
    }

    // GWT has JSONValue but that doesn't seem to be available in the version that we use.
    // It doesn't matter here because the Java code will treat it as an opaque value anyway.
    private native String getStateString(JavaScriptObject implementation, String defaultValue) /*-{
        return implementation && implementation.getState ? JSON.stringify(implementation.getState()) : defaultValue;
    }-*/;

    private String getStateString() {
        //NOTE We are not using implementationValid here because we want to try and use
        //     the current instance to retrieve the most up-to-date info even if we
        //     failed to retrieve pin info from it.
        if (implementation == null)
            return modelStateString;

        try {
            // We pass the current modelStateString (rather than "null") as the default value so we will
            // keep the value that was entered by the user (or loaded from a dump) if the model doesn't
            // support state.
            return getStateString(implementation, modelStateString);
        } catch (Exception e) {
            reportException("error while getting state of JavaScript object: ", e);
            return modelStateString;
        }
    }

    private native JavaScriptObject getImplementationFromJavaScript(String name, JavaScriptObject old, String stateString) /*-{
        var cls = $wnd[name];
        if (!cls)
            return null;
        if (old && cls.upgrade)
            return cls.upgrade(old);
        else if (old && old.getState)
            return new cls(old.getState());
        else
            return new cls(JSON.parse(stateString));
    }-*/;

    private native JsArrayString getInputs(JavaScriptObject implementation) /*-{ return implementation.inputs; }-*/;
    private native JsArrayString getOutputs(JavaScriptObject implementation) /*-{ return implementation.outputs; }-*/;
    private native boolean getNeedsTristate(JavaScriptObject implementation) /*-{
        return implementation && implementation.needsTristate === true;
    }-*/;
    private native String getInfoText(JavaScriptObject implementation, String defaultText) /*-{
        var infoText = implementation && implementation.infoText;
        if (!infoText)
            return defaultText;
        else if (typeof(infoText) == "function")
            return implementation.infoText();
        else
            return "" + infoText;
    }-*/;

    @Override
    void setupPins() {
        implementationValid = false;

        if (modelName == null) {
            // We are called from the constructor of ChipElm. Do hardly anything, yet.
            implementation = null;
            postCount = bits;
            allocNodes();
            return;
        }

        try {
            JavaScriptObject implementation2 = getImplementationFromJavaScript(modelName, implementation, modelStateString);
            if (implementation2 != null) {
                implementation = implementation2;
                javascriptErrors = 0;
                javascriptError = null;

                // log object to console so the user has a chance to interact with it
                // (e.g. by using "Store as Global Variable" in the console)
                console("JavaScript implementation for " + modelName + ":", implementation);
            } else {
                // We keep the existing implementation object because it still contains the most up-to-date state.
                console("We couldn't make a new instance of the model so we are keeping the old one.");
                javascriptError = "Class not found.";
            }
        } catch (Exception e) {
            reportException("error while making new JavaScript object: ", e);
        }

        if (implementation == null) {
            // We must add some pins so the element can be drawn.
            implementationValid = false;
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
            JsArrayString inputs = getInputs(implementation);
            JsArrayString outputs = getOutputs(implementation);
            inputCount = inputs.length();
            outputCount = outputs.length();
            sizeY = inputCount > outputCount ? inputCount : outputCount;
            if (sizeY == 0)
                sizeY = 1;
            sizeX = 2;
            postCount = inputCount+outputCount;
            pins = new Pin[postCount];
            int i;
            for (i = 0; i != inputCount; i++) {
                pins[i] = new Pin(i, SIDE_W, inputs.get(i));
                pins[i].fixName();
            }
            for (i = 0; i != outputCount; i++) {
                pins[i+inputCount] = new Pin(i, SIDE_E, outputs.get(i));
                pins[i+inputCount].output = true;
                pins[i+inputCount].fixName();
            }
            lastValues = new boolean[postCount];
            highImpedance = new boolean[postCount];
        } catch (Exception e) {
            reportException("error while getting pin information from JavaScript: ", e);
        }

        implementationValid = true;
    }

    int getPostCount() { return postCount; }
    
    @Override
    int getVoltageSourceCount() {
        return outputCount;
    }

    // keep track of whether we have any tri-state outputs.  if not, then we can simplify things quite a bit,
    // making the simulation faster
    boolean hasTriState() {
        try {
            return implementationValid ? false : getNeedsTristate(implementation);
        } catch (Exception e) {
            reportException("init inputs and outputs: error: ", e);
            return false;
        }
    }
    
    boolean nonLinear() { return hasTriState(); }

    @Override
    void reset() {
        super.reset();

        javascriptErrors = 0;
        if (implementation == null || !implementationValid)
            return;

        try {
            reset(implementation);
        } catch (Exception e) {
            reportException("Exception in Javascript implementation (reset) of circuit element:", e);
        }
    }

    int getInternalNodeCount() {
        // for tri-state outputs, we need an internal node to connect a voltage source to,
        // and then connect a resistor from there to the output.
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
    public static native void console(JavaScriptObject obj)
    /*-{
	    console.log(obj);
	}-*/;
    public static native void console(String text, JavaScriptObject obj)
    /*-{
	    console.log(text, obj);
	}-*/;

    private void reportException(String where, Exception e) {
        if (e instanceof JavaScriptException) {
            console(where, (JavaScriptObject)((JavaScriptException)e).getThrown());
        } else {
            console(where + e);
            e.printStackTrace();
        }
        javascriptError = where + e;
        javascriptErrors++;
    }

    private native JsArrayBoolean makeBoolArray() /*-{ return []; }-*/;
    private native JsArrayInteger executeStep(JavaScriptObject implementation, JavaScriptObject inputs, double time) /*-{
        var outputs = implementation.execute(inputs, time);
        return outputs.map(function (x) {
            if (x == 0 || x === false)
                return false;
            else if (x == 1 || x === true)
                return true;
            else if (x == -1)
                return -1;
            else {
                console.log("Unexpected value in return of Javascript implementation (executeStep) of circuit element:",
                    x, implementation, inputs, outputs);
                return -1;
            }
        });
    }-*/;
    private native boolean reset(JavaScriptObject implementation) /*-{
        if (implementation.reset)
            implementation.reset();
    }-*/;
    private native boolean startIteration(JavaScriptObject implementation, double time) /*-{
        if (implementation.startIteration)
            implementation.startIteration(time);
    }-*/;
    private native boolean stepFinished(JavaScriptObject implementation, double time) /*-{
        if (implementation.stepFinished)
            implementation.stepFinished(time);
    }-*/;
    private native boolean hasSetState(JavaScriptObject implementation) /*-{
        return implementation && typeof(implementation.setState) == "function";
    }-*/;
    private native void setState(JavaScriptObject implementation, String state) /*-{
        if (implementation.setState)
            implementation.setState(JSON.parse(state));
    }-*/;

    void startIteration() {
        if (implementation == null || !implementationValid || javascriptErrors > 10)
            return;

        try {
            startIteration(implementation, sim.t);
        } catch (Exception e) {
            reportException("Exception in Javascript implementation (startIteration) of circuit element:", e);
        }
    }
	void stepFinished() {
        if (implementation == null || !implementationValid || javascriptErrors > 10)
            return;

        try {
            stepFinished(implementation, sim.t);
        } catch (Exception e) {
            reportException("Exception in Javascript implementation (stepFinished) of circuit element:", e);
        }
    }

    void execute() {
        if (implementation == null || !implementationValid || javascriptErrors > 10)
            return;

        try {
            int i;
            JsArrayBoolean inputValues = makeBoolArray();
            for (i = 0; i < inputCount; i++) {
                inputValues.push(pins[i].value);
            }

            JsArrayInteger outputValues = executeStep(implementation, inputValues, sim.t);
            for (i = 0; i != outputValues.length() && i != outputCount; i++) {
                highImpedance[i+inputCount] = (outputValues.get(i) < 0);
                pins[i+inputCount].value = (outputValues.get(i) == 1);
            }
            
            // save values for transition checking
            int j;
            for (j = 0; j != postCount; j++)
                lastValues[j] = pins[j].value;
        } catch (Exception e) {
            reportException("Exception in Javascript implementation of circuit element: ", e);
        }
    }
    
    public EditInfo getChipEditInfo(int n) {
        if (n == 0) {
            EditInfo ei = new EditInfo("JavaScript Class", 0, -1, -1);
            ei.text = modelName;
            ei.disallowSliders();
            ei.newDialog = true;  // update edit dialog if model name is changed
            return ei;
        }
        if (n == 1) {
            EditInfo ei = new EditInfo("JavaScript State", 0, -1, -1);
            ei.text = modelStateString = getStateString();
            ei.disallowSliders();
            if (implementation != null && !hasSetState(implementation)) {
                // disable editing because any changes will be lost
                ei.textf = new TextBox();
                ei.textf.setValue(ei.text);
                ei.textf.setReadOnly(true);
				ei.textf.setVisibleLength(50);  // same as in EditDialog
                ei.widget = ei.textf;  // tell EditDialog to use our widget instead of making its own
            }
            return ei;
        }
        if (n == 2) {
            EditInfo ei = new EditInfo("JavaScript Info", 0, -1, -1);
            ei.text = javascriptError != null ? javascriptError : implementationValid ? "ok" : "not loaded";
            ei.disallowSliders();
            ei.widget = new Label(ei.text);
            if (javascriptError != null)
                ei.widget.getElement().getStyle().setColor("#f00");
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
        if (n == 1) {
            String newValue = ei.textf.getText();
            if (newValue.equals(modelStateString))
                return;
            modelStateString = newValue;
            try {
                if (implementation != null)
                    setState(implementation, newValue);
            } catch (Exception e) {
                reportException("Exception in Javascript implementation (setState) of circuit element:", e);
            }
        }
    }
    
    int getDumpType() { return 600; }
	int getShortcut() { return 'J'; }

    void getInfo(String arr[]) {
        super.getInfo(arr);
        try {
            arr[0] = getInfoText(implementation, modelName);
        } catch (Exception e) {
            reportException("Exception in Javascript implementation (infoText) of circuit element:", e);
            arr[0] = modelName;
        }
    }
}
