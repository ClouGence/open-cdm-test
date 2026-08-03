package com.clougence.test.framework.testcase;

import com.clougence.test.framework.resource.TextCaseSupport;

public abstract class TextCaseDescriptor {

    private final String resourcePath;
    private final String name;
    private final int    index;

    protected TextCaseDescriptor(TextCaseSupport.CaseBlock block){
        this(block.resourcePath(), block.name(), block.index());
    }

    protected TextCaseDescriptor(String resourcePath, String name, int index){
        this.resourcePath = resourcePath;
        this.name = name;
        this.index = index;
    }

    public final String resourcePath() {
        return resourcePath;
    }

    public final String name() {
        return name;
    }

    public final int index() {
        return index;
    }

    public final String caseId() {
        return resourcePath + "#" + name;
    }

    public final String caseIndexId() {
        return resourcePath + "#" + String.format("%03d", index);
    }
}
