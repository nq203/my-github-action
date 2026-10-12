package com.example.myGithubAction.execution_engine.worker;

/** All of this worker's execution slots are taken; the START message is retried later, not dropped. */
public class WorkerBusyException extends RuntimeException {
    public WorkerBusyException() {
        super("No free execution slot on this worker", null, false, false);
    }
}
