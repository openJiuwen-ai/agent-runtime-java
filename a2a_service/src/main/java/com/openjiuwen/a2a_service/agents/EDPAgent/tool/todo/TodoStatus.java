package com.openjiuwen.a2a_service.agents.EDPAgent.tool.todo;

public enum TodoStatus {
    PENDING("pending"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed"),
    CANCELLED("cancelled"),
    FAILED("failed");

    private final String value;

    TodoStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static TodoStatus fromValue(String value) {
        for (TodoStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown todo status: " + value);
    }
}
