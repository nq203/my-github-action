package com.example.myGithubAction.monitoring;

/** Transport-neutral output of a subscription (an SseEmitter adapter in production). */
public interface StreamSink {

    void send(String eventName, Object data);

    void complete();
}
