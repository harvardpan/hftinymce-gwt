package com.healthfortis.map.shared.client.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.google.gwt.core.client.Callback;
import com.google.gwt.core.client.JavaScriptException;
import com.google.gwt.core.client.JavaScriptObject;
import com.google.gwt.core.client.Scheduler;
import com.google.gwt.core.client.Scheduler.ScheduledCommand;
import com.google.gwt.core.client.ScriptInjector;
import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.event.dom.client.BlurEvent;
import com.google.gwt.event.dom.client.BlurHandler;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.event.dom.client.DomEvent;
import com.google.gwt.event.dom.client.FocusEvent;
import com.google.gwt.event.dom.client.FocusHandler;
import com.google.gwt.event.dom.client.KeyDownEvent;
import com.google.gwt.event.dom.client.KeyDownHandler;
import com.google.gwt.event.dom.client.MouseUpEvent;
import com.google.gwt.event.dom.client.MouseUpHandler;
import com.google.gwt.json.client.JSONBoolean;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONString;
import com.google.gwt.json.client.JSONValue;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.ui.TextArea;

public class HFRichTextEditor extends TextArea {

    public static final int TINYMCE_VERSION_3 = 3;
    public static final int TINYMCE_VERSION_4 = 4;

    public static final int DEFAULT_HEIGHT = 72;
    public static final int DEFAULT_WIDTH = 640;
    
    public static final int PENDING_COMMAND_UNLOAD = 0;
    public static final int PENDING_COMMAND_LOAD = 1;
    public static final int PENDING_COMMAND_SELECT_ALL = 2;
    public static final int PENDING_COMMAND_SET_HTML = 3;
    public static final int PENDING_COMMAND_SET_FOCUS = 4;
    
    public static int libraryVersion = TINYMCE_VERSION_4; // select which version of the library to load at runtime.
    
    private static boolean libraryLoaded = false;
    private static final String DEFAULT_ELEMENT_ID = "hfRichTextEditor";
    private static HashMap<String, HFRichTextEditor> activeEditors; // stores a mapping of elementId => instances of HFRichTextEditor
    static {
        activeEditors = new HashMap<String, HFRichTextEditor>();
    }
    private ArrayList<Integer> pendingCommands = new ArrayList<Integer>(); // handle multiple onLoad and unUnload events in succession
    
    @SuppressWarnings("rawtypes")
    private Set fixedOptions = new HashSet(2); // options that can not be overwritten
    private JSONObject options = new JSONObject(); // all other TinyMCE options
    private boolean initialized = false;
    private boolean initializing = false;
    private String elementId;
    private boolean focused;
    private SafeHtml pendingSetHtmlText = null;
    private static int numInitialized = 1; // start the count at "1"
    public HFRichTextEditor() {
        this(DEFAULT_ELEMENT_ID);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public HFRichTextEditor(String elementId, Map presetOptions)
    {
        // NOTE: Do NOT use GWT's debug id scheme. This includes UIObject.ensureDebugId and the debugId property of the UI Binder.
        //       Doing so will prevent the ID from working and this widget from being recognized properly.
        this.elementId = generateUniqueName(elementId);
        ++numInitialized;
        activeEditors.put(this.elementId, this);
        getElement().setId(this.elementId);
        getElement().addClassName(this.elementId);

        // fixed attributes
        addOption("selector", "textarea." + this.elementId);
        fixedOptions.addAll(options.keySet());
        // load preset
        if (presetOptions == null) {
            applyPreset(HFRichTextEditorPreset.getAdvancedOptions());
        } else {
            applyPreset(presetOptions);
        }
        
        //Add handlers to support method isFocused()
        addFocusHandler(new FocusHandler() {
            @Override
            public void onFocus(FocusEvent event) {
                focused = true;
            }
        });

        addBlurHandler(new BlurHandler() {
            @Override
            public void onBlur(BlurEvent event) {
                focused = false;
            }
        });
        
        final HFRichTextEditor me = this;
        addMouseUpHandler(new MouseUpHandler() {
            @Override
            public void onMouseUp(MouseUpEvent event) {
                setBookmarkPosition(me.elementId);
            }
        });
        addClickHandler(new ClickHandler() {
            @Override
            public void onClick(ClickEvent event) {
                setTextCursor(false);
                restoreBookmarkPosition(me.elementId);
            }
        });
        
        addKeyDownHandler(new KeyDownHandler() {
            @Override
            public void onKeyDown(KeyDownEvent event) {
                setBookmarkPosition(me.elementId);
            }            
        });
    }

    
    public HFRichTextEditor(String elementId)
    {
        this(elementId, null);
    }

    /**
     * Returns the HFRichTextEditor instance associated with a particular elementId. Used mainly
     * by the handler code to make sure that we end up propagating the handler sources correctly. There's
     * probably a better way to do this.
     * 
     * @param elementId The unique ID of the HFRichTextEditor
     * @return the HFRichTextEditor instance associated with the input elementId
     */
    private static HFRichTextEditor getActiveEditor(String elementId) {
        if (elementId == null || elementId.trim().isEmpty() || !activeEditors.containsKey(elementId.toLowerCase().trim())) {
            return null;
        }
        
        return activeEditors.get(elementId.toLowerCase().trim());
    }
    
    /*
     * Given a id string, it checks the activeEditors key set to make sure that
     * a unique name is generated. This is needed so that you don't instantiate
     * multiple editors with the same id (and thus not be able to address them
     * correctly in the javascript).
     */
    private static String generateUniqueName(String id) {
        if (id == null || id.trim().isEmpty()) {
            return "";
        }
        
        String usedNameKey = id.trim().toLowerCase();
        while (true) {
            String testKey = usedNameKey + Integer.toString(numInitialized);
            testKey = testKey.trim().toLowerCase();
            if (!activeEditors.keySet().contains(testKey)) {
                return testKey;
            }
            ++numInitialized;
        }
    }
    
    /**
     * Applies a set of options to the TinyMCE init method. This needs to be called prior to
     * init being called.
     * 
     * @param optionsMap the list of options, usually generated from HFRichTextEditorPreset class.
     */
    @SuppressWarnings("rawtypes")
    private void applyPreset(Map optionsMap) {
        for (Object optionKey : optionsMap.keySet()) {
            addOption((String) optionKey, optionsMap.get(optionKey));
        }
    }

    /**
     * Add an option dynamically into the set of options that will be used by the init function
     * for TinyMCE.
     * 
     * @param key a string value denoting the configuration parameter (see TinyMCE documentation)
     * @param value a value of Boolean, Integer, or String types.
     */
    public void addOption(String key, Object value)
    {
        // do not allow overriding fixed options
        if (fixedOptions.contains(key)) {
            return;
        }

        if (value instanceof Boolean) {
            options.put(key, JSONBoolean.getInstance((Boolean) value));
        } else if (value instanceof Integer) {
            options.put(key, new JSONNumber((Integer) value));
        } else if (value instanceof String) {
            options.put(key, new JSONString((String) value));
        } else {
            // Shouldn't ever really get to this, but just in case.
            options.put(key,  new JSONString(value.toString()));
        }
    }

    private void addPendingLoad() {
        if (!pendingCommands.isEmpty()
                && pendingCommands.contains(PENDING_COMMAND_UNLOAD)) {
            // A load and unload paired together is a no-op
            pendingCommands.remove(Integer.valueOf(PENDING_COMMAND_UNLOAD));
            return;
        }
        
        if (!pendingCommands.contains(PENDING_COMMAND_LOAD)) {
            // Everything that is pending before no longer matters if we're adding a load.
            pendingCommands.clear();
            pendingCommands.add(PENDING_COMMAND_LOAD);
        }
    }
    
    private void addPendingUnload() {
        if (!pendingCommands.isEmpty()
                && pendingCommands.contains(PENDING_COMMAND_LOAD)) {
            // A load and unload paired together is a no-op
            pendingCommands.remove(Integer.valueOf(PENDING_COMMAND_LOAD));
            return;
        }
        
        if (!pendingCommands.contains(PENDING_COMMAND_UNLOAD)) {
            pendingCommands.add(PENDING_COMMAND_UNLOAD);
        }
    }

    private void addPendingSelectAll() {
        if (!pendingCommands.contains(PENDING_COMMAND_SELECT_ALL)) {
            pendingCommands.add(PENDING_COMMAND_SELECT_ALL);
        }
    }

    private void addPendingSetHtml() {
        if (!pendingCommands.contains(PENDING_COMMAND_SET_HTML)) {
            pendingCommands.add(PENDING_COMMAND_SET_HTML);
        }
    }

    private void addPendingSetFocus() {
        if (!pendingCommands.contains(PENDING_COMMAND_SET_FOCUS)) {
            pendingCommands.add(PENDING_COMMAND_SET_FOCUS);
        }
    }
    
    @Override
    protected void onLoad()
    {
        super.onLoad();
        // Delay the initialization of the TinyMCE editor until after the current browser loop.
        // This will avoid issues where there are multiple loads and unloads.
        addPendingLoad();
        Scheduler.get().scheduleDeferred(new ScheduledCommand() {
            @Override
            public void execute() {
                runPendingCommand();
            }
        });        
    }

    @Override
    protected void onUnload() {
        try {
            if (!uninitialize()) {
                addPendingUnload();
            }
        } catch (JavaScriptException e) {
            GWT.log("Unable to clean up TinyMCE editor.", e);
        } finally {
            super.onUnload();
        }
    }
    
    private boolean initialize() {
        try {
            if (!isInitialized() && !isInitializing()) {
                unloadTinyMce(elementId); // clean up everything before initializing
                initTinyMce(elementId, options.getJavaScriptObject());
                setInitializing(true); // set this after since the above call is asynchronous. We don't want it to be true and an exception to be thrown
                
                // Even though this entry was set in the constructor, it could have been unset
                // in the unload function. We ensure that we have a valid reference while this
                // editor is initialized
                activeEditors.put(elementId, this);
                return true;
            }
        } catch (JavaScriptException e) {
            GWT.log("Unable to initialize the TinyMCE editor.", e);
        }
        return false;
    }
    
    private boolean uninitialize() {
        try {
            if (isInitialized()) {
                unloadTinyMce(elementId);            
                setInitialized(false);
                setInitializing(false);
                
                // Clear the entry in activeEditors so that we don't hold the memory if it needs to be cleaned up.
                activeEditors.put(elementId, null);
                activeEditors.remove(elementId);
                return true;
            }                
        } catch (JavaScriptException e) {
            GWT.log("Unable to uninitialize the TinyMCE editor.", e);
        }
        return false;        
    }
    
    private void runPendingCommand()
    {
        if (!libraryLoaded) {
            // Only perform operations specific to TinyMCE if the library was successfully loaded
            return;
        }
        
        if (pendingCommands.isEmpty()) {
            return;
        }
        
        Integer pendingCommand = pendingCommands.get(0);
        while (pendingCommand != null) {
            if (pendingCommand == PENDING_COMMAND_LOAD) {
                if (initialize()) {
                    break; // we break because a load is asynchronous. We can't process any of the other commands until later.
                }
            } else if (pendingCommand == PENDING_COMMAND_UNLOAD) {
                uninitialize();
            } else if (pendingCommand == PENDING_COMMAND_SELECT_ALL) {
                selectAll();
            } else if (pendingCommand == PENDING_COMMAND_SET_HTML) {
                setHTML(pendingSetHtmlText);
                pendingSetHtmlText = null;
            } else if (pendingCommand == PENDING_COMMAND_SET_FOCUS) {
                if (focused) {
                    setFocus(true);
                }
            }
            pendingCommands.remove(0);
            pendingCommand = pendingCommands.isEmpty() ? null : pendingCommands.get(0);
        }
    }

    private native void initTinyMce(String elementId, JavaScriptObject options)
    /*-{
        $wnd.tinymce.init(options);
    }-*/;

    private native void unloadTinyMce(String elementId)
    /*-{
        var editorsToDelete = [];
        // First collect all the editors to delete. Firefox doesn't like it when we delete in place
        // since it does end up modifying the editors array.
        for (edId in $wnd.tinymce.editors) {
            if ($wnd.tinymce.editors[edId].id === elementId) {
                editorsToDelete.push($wnd.tinymce.editors[edId]);
            }
        }
        // Have a second loop to actually delete the editors.
        for (var index = editorsToDelete.length - 1; index >= 0; index--) {
            var myEditor = editorsToDelete[index];
            if (myEditor == null) {
                continue;
            }
            delete myEditor.updatedSelectionBookmark;
            myEditor.updatedSelectionBookmark = null;
            myEditor.remove();
            if (myEditor == null) {
                continue;
            }
            myEditor.destroy();
            myEditor = null;
        }
        // Clear the array by setting its length to 0.
        editorsToDelete.length = 0;
        // Null out the reference altogether
        editorsToDelete = null;
    }-*/;
    
    public static boolean loadLibrary() {
        String scriptUrl = "";
        if (libraryVersion == TINYMCE_VERSION_4) {
            scriptUrl = GWT.getModuleBaseURL() + "tinymce4/tinymce.min.js";
        } else if (libraryVersion == TINYMCE_VERSION_3) {
//            scriptUrl = GWT.getModuleBaseURL() + "tinymce3/tiny_mce.js";
            return false; // Security Audit found an issue with using eval. Removing from code base for now. 4.20.2015
        } else {
            // No such version allowed.
            return false;
        }
        JavaScriptObject scriptInstance = ScriptInjector.fromUrl(scriptUrl)
            .setWindow(ScriptInjector.TOP_WINDOW)
            .setCallback(new Callback<Void, Exception>() {

                @Override
                public void onFailure(Exception reason) {
                    Window.alert("TinyMCE failed to load.");
                    libraryLoaded = false;
                }

                @Override
                public void onSuccess(Void result) {
                    // We need to register the Javascript method that will handle all of the events.
                    registerEventHandler();
                    libraryLoaded = true;
                }                
            }).inject();
        return (scriptInstance != null);
    }

    public static void setupCallback(String elementId) {
        // Add actions here to take after the setup of the editor is complete (usually when event handlers are added)
    }
    
    public static void initCallback(String elementId) {
        // Add actions here to take after the editor has been properly initialized. Usually, calls are made to the widget
        // such as setSize, setTabIndex - before the editor is initialized. We make those calls here to ensure that they
        // get set properly.
        
        final String elementIdFinal = elementId; 
        Scheduler.get().scheduleDeferred(new ScheduledCommand() {
            @Override
            public void execute() {
                HFRichTextEditor editorInstance = getActiveEditor(elementIdFinal);
                if (editorInstance != null) {
                    editorInstance.setControlTabIndex(elementIdFinal); // set up the tabIndex that comes from the hidden textarea (if it exists)
                    JSONValue pxWidth = editorInstance.getOptions().get("width");
                    if (pxWidth == null) {
                        pxWidth = new JSONNumber(DEFAULT_WIDTH);
                    }
                    JSONValue pxHeight = editorInstance.getOptions().get("height");
                    if (pxHeight == null) {
                        pxHeight = new JSONNumber(DEFAULT_HEIGHT);
                    }
                    editorInstance.setControlSize(elementIdFinal, pxWidth, pxHeight);
                    
                    editorInstance.setInitialized(true);
                    editorInstance.setInitializing(false);
                    // If there were any pending unload commands, we run them here.
                    editorInstance.runPendingCommand();
                }
                
            }
        });        
        
    }

    /**
     * This gets called by the JSNI code for every event that we want to handle.
     * @param elementId the unique ID of the HFRichTextEditor. Used to locate the source of the event.
     * @param event the NativeEvent that we will fire into the GWT event handling loop
     */
    public static void handleNativeEvent(String elementId, Object event) {
        if (event instanceof NativeEvent) {
            HFRichTextEditor editorInstance = getActiveEditor(elementId);
            if (editorInstance != null) {
                DomEvent.fireNativeEvent((NativeEvent) event, editorInstance);
            }
        }
    }
    
    /**
     * This is the native JavaScript code that will create the callbacks needed for integration between GWT and
     * TinyMCE library. It's called upon loading the library so that each time an editor is initialized, it can
     * call the appropriate callback functions to properly register all the handlers for that editor.
     */
    private native static void registerEventHandler() 
    /*-{
        $wnd.hfrichtexteditorSetupCallback = 
            $entry(
                function(e) { 
                    @com.healthfortis.map.shared.client.ui.HFRichTextEditor::setupCallback(Ljava/lang/String;)(e.id);
                    e.on('mousedown', function(ev) {
                        @com.healthfortis.map.shared.client.ui.HFRichTextEditor::handleNativeEvent(Ljava/lang/String;Ljava/lang/Object;)(e.id, ev);
                    });
                    e.on('mouseup', function(ev) {
                        @com.healthfortis.map.shared.client.ui.HFRichTextEditor::handleNativeEvent(Ljava/lang/String;Ljava/lang/Object;)(e.id, ev);
                    });
                    e.on('keyup', function(ev) {
                        @com.healthfortis.map.shared.client.ui.HFRichTextEditor::handleNativeEvent(Ljava/lang/String;Ljava/lang/Object;)(e.id, ev);
                    });
                    e.on('keydown', function(ev) {
                        @com.healthfortis.map.shared.client.ui.HFRichTextEditor::handleNativeEvent(Ljava/lang/String;Ljava/lang/Object;)(e.id, ev);
                        // Prevent Ctrl+S from propagating to the TinyMCE editor. We don't want to save to external files.
                        if (ev.ctrlKey || ev.metaKey) {
                            switch (String.fromCharCode(ev.which || ev.keyCode).toLowerCase()) {
                            case 's':
                                ev.preventDefault();
                                if (ev.stopPropagation) {
                                    ev.stopPropagation();
                                } else {
                                    ev.cancelBubble = true;
                                }
                                break;
                            }
                        }
                    });
                    e.on('keypress', function(ev) {
                        @com.healthfortis.map.shared.client.ui.HFRichTextEditor::handleNativeEvent(Ljava/lang/String;Ljava/lang/Object;)(e.id, ev);
                    });
                    e.on('blur', function(ev) {
                        @com.healthfortis.map.shared.client.ui.HFRichTextEditor::handleNativeEvent(Ljava/lang/String;Ljava/lang/Object;)(e.id, ev);
                    });
                    e.on('focus', function(ev) {
                        @com.healthfortis.map.shared.client.ui.HFRichTextEditor::handleNativeEvent(Ljava/lang/String;Ljava/lang/Object;)(e.id, ev);
                    });
                    e.on('click', function(ev) {
                        @com.healthfortis.map.shared.client.ui.HFRichTextEditor::handleNativeEvent(Ljava/lang/String;Ljava/lang/Object;)(e.id, ev);
                    });
                }
            );
        $wnd.hfrichtexteditorInitCallback = 
            $entry(
                function(e) { 
                    @com.healthfortis.map.shared.client.ui.HFRichTextEditor::initCallback(Ljava/lang/String;)(e.id);
                }
            );            
    }-*/;    
    
    public SafeHtml getHTML() {
        SafeHtml result = null;
        if (libraryLoaded && initialized) {
            try {
                String contentHtml = getContentHtml(elementId); // TinyMCE takes care of the sanitization.
                if (contentHtml == null || contentHtml.trim().isEmpty()) {
                    return SafeHtmlUtils.fromSafeConstant("");
                }
                // Remove the root block <p></p> that gets added automatically by TinyMCE
                if (contentHtml.startsWith("<p>") && contentHtml.endsWith("</p>")) {
                    contentHtml = contentHtml.substring(3, contentHtml.length() - 4);
                }
                result = SafeHtmlUtils.fromTrustedString(contentHtml); 
            } catch (JavaScriptException e) {
                GWT.log("Unable to get the content from the TinyMCE editor.", e);
            }
        } else {
            String text = super.getText();
            if (text == null || text.trim().isEmpty()) {
                return SafeHtmlUtils.fromSafeConstant("");
            } else {
                return SafeHtmlUtils.fromString(text);
            }
        }
        return result;        
    }
    
    public String getText()
    {
        String result = "";
        if (libraryLoaded && initialized) {
            try {
                String contentText = getContentText(elementId);
                if (contentText == null) {
                    contentText = "";
                }
                result = SafeHtmlUtils.fromString(contentText).asString(); // requested as text, so we need to escape the string
            } catch (JavaScriptException e) {
                GWT.log("Unable to get the content from the TinyMCE editor.", e);
            }
        } else {
            result = super.getText();
            if (result == null || result.trim().isEmpty()) {
                result = "";
            } else {
                result = SafeHtmlUtils.fromString(result).asString();
            }
        }
        return result;
    }
    
    private native String getContentHtml(String elementId)
    /*-{
        var myEditor = $wnd.tinymce.get(elementId);
        if (myEditor == null) {
            return "";
        }
        $wnd.tinyMCE.triggerSave();
        return myEditor.getContent();
    }-*/;

    private native String getContentText(String elementId)
    /*-{
        var myEditor = $wnd.tinymce.get(elementId);
        if (myEditor == null) {
            return "";
        }
        $wnd.tinyMCE.triggerSave();
        return myEditor.getContent({ format : "text" });
    }-*/;
    
    public void setHTML(SafeHtml html) {
        String text = html == null ? null: html.asString();
        if (libraryLoaded && (isInitialized() || isInitializing())) {
            if (isInitializing()) {
                pendingSetHtmlText = html;
                addPendingSetHtml();
                return;
            }
            try {
                setContent(elementId, text);
            } catch (JavaScriptException e) {
                // Don't do anything, just allow it to return.
                GWT.log("Unable to set the content on the TinyMCE editor.", e);
            }
            return;
        } else {
            super.setText(text);
        }
    }

    /*
     * Will automatically escape the string and put it into the widget
     */
    @Override
    public void setText(String text)
    {
        String htmlText = text == null ? "" : text;
        setHTML(SafeHtmlUtils.fromString(htmlText));
    }

    private native String setContent(String elementId, String text)
    /*-{
        var myEditor = $wnd.tinymce.get(elementId);
        if (myEditor != null) {                
           myEditor.setContent(text);
        }
    }-*/;
    
    private native void setBookmarkPosition(String elementId) 
    /*-{
        var myEditor = $wnd.tinymce.get(elementId);
        if (myEditor == null) {                
           return;
        }
        myEditor.updatedSelectionBookmark = myEditor.selection.getBookmark(1);
    }-*/;
    
    private native void restoreBookmarkPosition(String elementId) 
    /*-{
        var myEditor = $wnd.tinymce.get(elementId);
        if (myEditor == null) {                
           return;
        }
        myEditor.updatedSelectionBookmark && myEditor.selection.moveToBookmark(myEditor.updatedSelectionBookmark);         
    }-*/;

    @Override
    public void selectAll() {
        if (isInitializing()) {
            addPendingSelectAll();
        } else if (!isInitialized()) {
            // It's not even initialized. We don't do anything.
            return;
        }
        // If it's properly initialized, then we do the select all.
        selectAllContent(elementId);
    }
    
    private native void selectAllContent(String elementId) 
    /*-{
        var myEditor = $wnd.tinymce.get(elementId);
        if (myEditor != null) {                
            myEditor.selection.select(myEditor.getBody(), true);
        }
    }-*/;

    /**
     * Puts the text cursor at the beginning or end of the body text.
     * 
     * @param atBeginning true if the cursor should be set at the beginning, false if at the end.
     */
    public void setTextCursor(boolean atBeginning) {
        if (!isInitialized()) {
            // It's not even initialized. We don't do anything.
            return;
        }
        // If it's properly initialized, we first select all, then collapse the selection
        // to either the beginning or the end.
        selectAllContent(elementId);
        collapseSelection(elementId, atBeginning);
    }

    private native void collapseSelection(String elementId, boolean atBeginning) 
    /*-{
        var myEditor = $wnd.tinymce.get(elementId);
        if (myEditor != null) {                
            myEditor.selection.collapse(atBeginning);
        }
    }-*/;
    
    @Override
    public String getSelectedText() {
        return getSelectedContent(elementId);
    }
    
    private native String getSelectedContent(String elementId) 
    /*-{
        var myEditor = $wnd.tinymce.get(elementId);
        if (myEditor != null) {                
            return myEditor.selection.getContent();      
        }
        return "";
    }-*/;
    
    private JSONValue parseSizeDimension(String dimension) {
        JSONValue pxDimension = new JSONNumber(0);
        if (dimension.endsWith("px")) {
            dimension = dimension.substring(0, dimension.length() - 2);
            pxDimension = new JSONNumber(Integer.parseInt(dimension.trim()));
        } else if (dimension.endsWith("em")) {
            dimension = dimension.substring(0, dimension.length() - 2);
            // 1em == 16px
            pxDimension = new JSONNumber((int) Float.parseFloat(dimension.trim()) * 16);
        } else if (dimension.endsWith("%")) {
            // eg. 100%
            pxDimension = new JSONString(dimension);
        } else {
            // A straight number is equivalent to pixels
            pxDimension = new JSONNumber(Integer.parseInt(dimension.trim()));                    
        }
        return pxDimension;
    }
        
    @Override
    public void setSize(String width, String height) {
        setWidth(width);
        setHeight(height);
    }
    
    private native void setControlSize(String elementId, JSONValue pxWidth, JSONValue pxHeight) 
    /*-{
        var myEditor = $wnd.tinymce.get(elementId);
        if (myEditor != null) {
            // Try multiple methods to set the size. Can't seem to get the size to stick in Chrome and Firefox
            myEditor.dom.setStyle(elementId + '_ifr', 'height', pxHeight + 'px');
            myEditor.dom.setStyle(elementId + '_ifr', 'width', pxWidth + 'px');            
            myEditor.theme.resizeTo(pxWidth, pxHeight);
        }
    }-*/;
    
    @Override
    public void setWidth(String width) {
        super.setWidth(width);
        try {
            JSONValue pxWidth = parseSizeDimension(width);
            addOption("width", pxWidth);
            if (libraryLoaded && initialized) {
                JSONValue pxHeight = options.get("height");
                if (pxHeight == null) {
                    pxHeight = new JSONNumber(DEFAULT_HEIGHT);
                }
                setControlSize(elementId, pxWidth, pxHeight);
            }
        } catch (JavaScriptException e) {
            // Don't do anything, just allow it to return.
            GWT.log("Unable to set the width on the TinyMCE editor.", e);
        }
    }
    
    @Override
    public void setHeight(String height) {
        super.setHeight(height);
        try {            
            JSONValue pxHeight = parseSizeDimension(height);
            addOption("height", pxHeight);
            if (libraryLoaded && initialized) {
                JSONValue pxWidth = options.get("width");
                if (pxWidth == null) {
                    pxWidth = new JSONNumber(DEFAULT_WIDTH);
                }
                setControlSize(elementId, pxWidth, pxHeight);
            }
        } catch (JavaScriptException e) {
            // Don't do anything, just allow it to return.
            GWT.log("Unable to set the height on the TinyMCE editor.", e);
        }
    }   
        
    /*
     * Returns whether the current object is the one that has the focus.
     */
    public boolean isFocused() {
        return focused;
    }    
    
    public void setFocus(boolean focused) {
        if (!focused) {
            super.setFocus(focused); // nothing we can do to unset focus. Let GWT handle it.
            return;
        }
        
        try {
            // Only pass along the focus command 
            if (libraryLoaded && (isInitialized() || isInitializing())) {
                if (isInitializing()) {
                    // Setting a pending focus actually messes up the final focused element.
                    // This is because the async call always returns last, so any calls to setFocus
                    // for any other element essentially get ignored.
//                    if (focused) {
//                        addPendingSetFocus();
//                    }
                    return;
                }
                try {
                    setControlFocus(elementId);
                } catch (JavaScriptException e) {
                    GWT.log("Unable to set the focus on the TinyMCE editor.", e);
                }
                return;
            } 
        } finally {
            super.setFocus(focused);
        }
    }
    
    private native void setControlFocus(String elementId)
    /*-{
        var myEditor = $wnd.tinymce.get(elementId);
        if (myEditor != null) {
            myEditor.focus();
        }
    }-*/;
    
    public void setTabIndex(int tabIndex) {
        super.setTabIndex(tabIndex); // sets the tabIndex for the hidden associated textarea

        if (!libraryLoaded || !initialized) {
            // If not loaded or initialized, we're done.
            return;
        }
        
        try {
            // This will move the value in the textArea to the iframe
            setControlTabIndex(elementId);
        } catch (JavaScriptException e) {
            GWT.log("Unable to set the tab index on the TinyMCE editor iframe.", e);
        }    
    }

    /**
     * This will move the tabIndex from the textArea to the iframe.
     * 
     * @param elementId the elementId to uniquely identify the textarea
     */
    private native void setControlTabIndex(String elementId)
    /*-{
        var myEditor = $wnd.tinymce.get(elementId);
        if (myEditor != null) {
            var textAreaElement = null;
            if ($doc.getElementById) {
                textAreaElement = $doc.getElementById(elementId); // element replaced by TinyMCE
            }
            if (textAreaElement == null) {
                return;
            }
            var originalTabIndex = 0; // tabindex of element, or 0            
            if (textAreaElement.getAttribute && textAreaElement.getAttribute('tabindex')) {
                originalTabIndex = textAreaElement.getAttribute('tabindex');
            }
            var iframeTextEditor = $doc.getElementById(elementId + '_ifr'); // editor iframe element
            if (iframeTextEditor == null || !iframeTextEditor.setAttribute) {
                return;
            }
            iframeTextEditor.setAttribute('tabindex', originalTabIndex); // set iframe tabindex            
        }
    }-*/;

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        if (visible) {            
            // Call initialize in case it wasn't previously initialized because the widget was hidden.
            initialize();
        }
    }
    
    public JSONObject getOptions() {
        return options;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void setInitialized(boolean initialized) {
        this.initialized = initialized;
    }

    public boolean isInitializing() {
        return initializing;
    }

    public void setInitializing(boolean initializing) {
        this.initializing = initializing;
    }    
}
