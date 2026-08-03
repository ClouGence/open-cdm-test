package com.clougence.test.framework;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public final class TestQueue {

    private final BlockingQueue<TestTask> queue;

    public TestQueue(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("queue capacity must be greater than 0");
        }
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    public void put(TestTask task) throws InterruptedException {
        queue.put(task);
    }

    public TestTask take() throws InterruptedException {
        return queue.take();
    }

    public int size() {
        return queue.size();
    }

    public int capacity() {
        return queue.size() + queue.remainingCapacity();
    }
}
