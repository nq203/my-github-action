package com.example.myGithubAction.execution_engine.exception;

/**
 * Command execution exception.
 *
 * Thrown when bash command execution fails.
 */
public class CommandExecutionException extends ExecutionException {

    private String command;
    private int exitCode;

    public CommandExecutionException(String message) {
        super(message);
    }

    public CommandExecutionException(String message, Throwable cause) {
        super(message, cause);
    }

    public CommandExecutionException(String message, String command, int exitCode) {
        super(message);
        this.command = command;
        this.exitCode = exitCode;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public int getExitCode() {
        return exitCode;
    }

    public void setExitCode(int exitCode) {
        this.exitCode = exitCode;
    }
}
