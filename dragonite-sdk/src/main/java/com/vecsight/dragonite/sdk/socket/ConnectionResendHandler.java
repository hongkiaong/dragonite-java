package com.vecsight.dragonite.sdk.socket;

import com.vecsight.dragonite.sdk.misc.DragoniteGlobalConstants;
import com.vecsight.dragonite.sdk.misc.NumUtils;
import com.vecsight.dragonite.sdk.msg.ReliableMessage;

import java.io.IOException;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class ConnectionResendHandler {

    private final DragoniteSocket socket;

    private final SendAction sendAction;

    private final ConnectionSharedData sharedData;

    private final int minResendMS;

    private final Thread resendThread;

    private volatile boolean running = true;

    private final ConcurrentMap<Integer, ReliableMessage> messageConcurrentMap = new ConcurrentHashMap<>();

    private final Object queueLock = new Object();

    /*private final PriorityBlockingQueue<ResendItem> resendQueue =
            new PriorityBlockingQueue<>(100, (o1, o2) -> (int) (o1.getNextSendTime() - o2.getNextSendTime()));*/

    private final PriorityQueue<ResendItem> riQueue = new PriorityQueue<>(100,
            (o1, o2) -> (int) (o1.getNextSendTime() - o2.getNextSendTime()));

    protected ConnectionResendHandler(DragoniteSocket socket, SendAction sendAction, ConnectionSharedData sharedData, int minResendMS) {
        this.socket = socket;
        this.sendAction = sendAction;
        this.sharedData = sharedData;
        this.minResendMS = minResendMS;

        resendThread = new Thread(() -> {
            try {
                while (canRun()) {

                    final long sleepTime;
                    final int sequence;

                    ResendItem resendItem;

                    /*synchronized (queueLock) {
                        resendItem = resendQueue.take();
                        sleepTime = resendItem.getNextSendTime() - System.currentTimeMillis();
                        sequence = resendItem.getSequence();

                        resendItem.addSendCount();
                        resendItem.setNextSendTime(getNextSendTime(resendItem.getSendCount(), NumUtils.max(sleepTime, 0)));
                        resendQueue.put(resendItem);
                    }*/

                    synchronized (queueLock) {
                        while ((resendItem = riQueue.poll()) == null && canRun()) {
                            queueLock.wait();
                        }
                        if (resendItem == null) {
                            break;
                        }
                        sleepTime = resendItem.getNextSendTime() - System.currentTimeMillis();
                        sequence = resendItem.getSequence();

                        resendItem.addSendCount();
                        resendItem.setNextSendTime(getNextSendTime(resendItem.getSendCount(), NumUtils.max(sleepTime, 0)));
                        riQueue.add(resendItem);
                    }

                    if (sleepTime > 0) {
                        Thread.sleep(sleepTime);
                    }
                    //sleep done
                    final ReliableMessage message = messageConcurrentMap.get(sequence);

                    if (message != null) {
                        try {
                            resendItem.setResended();
                            sendAction.sendPacket(message.toBytes());
                            //System.out.println(message.getSequence());
                        } catch (IOException ignored) {
                        }
                    }

                }
            } catch (InterruptedException ignored) {
                //okay
            }
        }, "DS-Resend");
        resendThread.start();
    }

    private boolean canRun() {
        return running && socket.isAlive();
    }

    private long getNextSendTime(int count, long timeOffset) {
        //int resendMult = count <= fastResendMaxCount ? 1 : (int) Math.pow(1.5, count - fastResendMaxCount);
        //int delay = (int) (sharedData.getEstimatedRTT() * (count <= fastResendMaxCount ? DragoniteGlobalConstants.fastResendMul : DragoniteGlobalConstants.slowResendMul));
        long drtt = NumUtils.max(DragoniteGlobalConstants.DEV_RTT_MULT * sharedData.getDevRTT(), DragoniteGlobalConstants.MIN_RESEND_WAIT_MS);
        int delay = (int) (sharedData.getEstimatedRTT() + drtt);
        if (delay < minResendMS) {
            delay = minResendMS;
        }
        return System.currentTimeMillis() + delay + timeOffset;
    }

    protected void addMessage(ReliableMessage message) {
        messageConcurrentMap.put(message.getSequence(), message);
        synchronized (queueLock) {
            riQueue.add(new ResendItem(message.getSequence(), System.currentTimeMillis(), getNextSendTime(0, 0)));
            queueLock.notifyAll();

        }
    }

    //only returns valid (existing & unresended) RTTs or -1
    protected ResendInfo removeMessage(int sequence) {
        final ResendInfo resendInfo = new ResendInfo();
        synchronized (queueLock) {
            riQueue.removeIf(resendItem -> {
                if (resendItem.getSequence() == sequence) {
                    resendInfo.setExist(true);
                    resendInfo.setResended(resendItem.isResended());
                    resendInfo.setRTT(System.currentTimeMillis() - resendItem.getStartTime());
                    return true;
                } else {
                    return false;
                }
            });
            messageConcurrentMap.remove(sequence);
        }
        return resendInfo;
    }

    protected int queueTaskCount() {
        int size;
        synchronized (queueLock) {
            size = riQueue.size();
        }
        return size;
    }

    protected void close() {
        running = false;
        resendThread.interrupt();
        synchronized (queueLock) {
            queueLock.notifyAll();
            riQueue.clear();
            messageConcurrentMap.clear();
        }
    }

}
