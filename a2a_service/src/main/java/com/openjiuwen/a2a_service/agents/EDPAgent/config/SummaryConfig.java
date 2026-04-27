package com.openjiuwen.a2a_service.agents.EDPAgent.config;

import java.util.ArrayList;
import java.util.List;

public class SummaryConfig {

    private String format = "需求概述→规划过程→任务执行情况→结果汇总→异常说明";
    private int maxLength = 500;
    private List<String> requiredFields = new ArrayList<String>();

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public int getMaxLength() {
        return maxLength;
    }

    public void setMaxLength(int maxLength) {
        this.maxLength = maxLength;
    }

    public List<String> getRequiredFields() {
        return requiredFields;
    }

    public void setRequiredFields(List<String> requiredFields) {
        this.requiredFields = requiredFields != null ? new ArrayList<String>(requiredFields) : new ArrayList<String>();
    }
}
