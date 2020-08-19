package org.minbox.framework.message.pipe.server.distribution;

import com.alibaba.fastjson.JSON;
import io.grpc.ManagedChannel;
import lombok.extern.slf4j.Slf4j;
import org.minbox.framework.message.pipe.core.ClientInformation;
import org.minbox.framework.message.pipe.core.Message;
import org.minbox.framework.message.pipe.core.grpc.MessageServiceGrpc;
import org.minbox.framework.message.pipe.core.grpc.proto.MessageRequest;
import org.minbox.framework.message.pipe.core.transport.MessageRequestBody;
import org.minbox.framework.message.pipe.server.ClientManager;
import org.minbox.framework.message.pipe.server.LockNames;
import org.minbox.framework.message.pipe.server.MessagePipe;
import org.minbox.framework.message.pipe.server.config.MessagePipeConfiguration;
import org.minbox.framework.message.pipe.server.exception.ExceptionHandler;
import org.minbox.framework.message.pipe.server.lb.ClientLoadBalanceStrategy;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Execute messages in the distribution {@link MessagePipe}
 *
 * @author 恒宇少年
 */
@Slf4j
public class MessageDistributionExecutor {
    private ExecutorService executorService = Executors.newFixedThreadPool(1);
    private String pipeName;
    private RedissonClient redissonClient;
    private MessagePipeConfiguration configuration;

    public MessageDistributionExecutor(String pipeName, RedissonClient redissonClient, MessagePipeConfiguration configuration) {
        this.pipeName = pipeName;
        this.redissonClient = redissonClient;
        this.configuration = configuration;
    }

    /**
     * Waiting for new news
     * <p>
     * After discovering a new message from the message pipeline, perform distribution to the client
     */
    public void waitingForNewMessage() {
        executorService.submit(() -> {
            for (; ; ) {
                try {
                    this.takeAndSend();
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                }
            }
        });
    }


    /**
     * task a message
     * <p>
     * take and remove the first message from current {@link MessagePipe}
     *
     * @return The {@link Message} instance
     */
    private void takeAndSend() {
        Message message = null;
        String takeLockName = LockNames.TAKE_MESSAGE.format(this.pipeName);
        RLock takeLock = redissonClient.getLock(takeLockName);
        takeLock.lock();
        if (!Thread.currentThread().isInterrupted()) {
            try {
                String queueLockName = LockNames.MESSAGE_QUEUE.format(this.pipeName);
                RBlockingQueue<Message> queue = redissonClient.getBlockingQueue(queueLockName);
                message = queue.peek();
                System.out.println(new String(message.getBody()));
                if (message != null) {
                    this.sendMessage(message);
                    queue.poll();
                }
            } catch (Exception e) {
                ExceptionHandler exceptionHandler = this.configuration.getExceptionHandler();
                exceptionHandler.handleException(e, message);
            } finally {
                takeLock.unlock();
            }
        }
    }

    /**
     * Send {@link Message} to client
     *
     * @param message The {@link Message} instance
     */
    private void sendMessage(Message message) {
        List<ClientInformation> clients = ClientManager.getPipeBindClients(this.pipeName);
        ClientLoadBalanceStrategy strategy = this.configuration.getLoadBalanceStrategy();
        ClientInformation clientInformation = strategy.lookup(clients);
        String clientId = ClientManager.getClientId(clientInformation.getAddress(), clientInformation.getPort());
        ManagedChannel channel = ClientManager.establishClientChannel(clientInformation);
        MessageServiceGrpc.MessageServiceBlockingStub messageClientStub = MessageServiceGrpc.newBlockingStub(channel);
        String requestId = this.generatorRequestId();
        MessageRequestBody requestBody =
                new MessageRequestBody()
                        .setRequestId(requestId)
                        .setClientId(clientId)
                        .setMessage(message)
                        .setPipeName(this.pipeName);
        messageClientStub.sendMessage(MessageRequest.newBuilder().setBody(JSON.toJSONString(requestBody)).build());
        log.debug("To the client: {}, sending the message is complete.", clientId);
    }

    /**
     * generator requestId
     *
     * @return The new requestId
     */
    private String generatorRequestId() {
        return UUID.randomUUID().toString();
    }
}
