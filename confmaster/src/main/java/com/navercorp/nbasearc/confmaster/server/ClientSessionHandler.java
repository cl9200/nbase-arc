/*
 * Copyright 2015 Naver Corp.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.navercorp.nbasearc.confmaster.server;

import static com.navercorp.nbasearc.confmaster.Constant.*;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;

import org.apache.zookeeper.KeeperException;

import com.navercorp.nbasearc.confmaster.ConfMasterException.MgmtCommandNotFoundException;
import com.navercorp.nbasearc.confmaster.ConfMasterException.MgmtCommandWrongArgumentException;
import com.navercorp.nbasearc.confmaster.config.Config;
import com.navercorp.nbasearc.confmaster.io.ClientSession;
import com.navercorp.nbasearc.confmaster.io.EventSelector;
import com.navercorp.nbasearc.confmaster.io.LineReader;
import com.navercorp.nbasearc.confmaster.io.Session;
import com.navercorp.nbasearc.confmaster.io.SessionHandler;
import com.navercorp.nbasearc.confmaster.logger.Logger;
import com.navercorp.nbasearc.confmaster.server.JobResult.CommonKey;
import com.navercorp.nbasearc.confmaster.server.command.CommandCallback;
import com.navercorp.nbasearc.confmaster.server.command.CommandExecutor;
import com.navercorp.nbasearc.confmaster.server.leaderelection.LeaderElectionHandler;
import com.navercorp.nbasearc.confmaster.server.leaderelection.LeaderState;
import com.navercorp.nbasearc.confmaster.statistics.Statistics;

public class ClientSessionHandler implements SessionHandler {

    public static enum ReplyType {
        NORMAL, REDIRECT
    }

    private ClientSession session;
    private long lastUpdatedTime;

    private final Integer MAX_BUFFER_SIZE;
    private final ByteBuffer recvBuffer;
    private ByteBuffer sendBuffer;
    private final CommandExecutor commandExecutor;
    private final LineReader lineReader;
    private final Charset charset;
    private final LeaderElectionHandler leaderElectionHandler;
    private final EventSelector eventSelector;
    private final ReplyFormatter formatter;
    private final Config config;
    
    public ClientSessionHandler(CommandExecutor commandTemplate,
            LeaderElectionHandler leaderElectionHandler, Config config,
            EventSelector eventSelector) {
        this.MAX_BUFFER_SIZE = config.getServerClientBufferSize();
        this.recvBuffer = ByteBuffer.allocate(102400);
        this.commandExecutor = commandTemplate;
        this.lineReader = new LineReader(Charset.forName(config.getCharset()).newDecoder());
        this.charset = Charset.forName(config.getCharset());
        this.leaderElectionHandler = leaderElectionHandler;
        this.eventSelector = eventSelector;
        this.formatter = new ReplyFormatter();
        this.config = config;
    }

    @Override
    public void callbackOnLoop(long timeMillis) {
        // Do nothing...
    }

    @Override
    public void callbackAccept(SelectionKey key, long timeMillis) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void callbackConnect(SelectionKey key, long timeMillis) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void callbackDisconnected() {
        eventSelector.removeSession(session.getID());
    }
    
    @Override
    public void callbackRead(SelectionKey key, long timeMillis) {
        assert key.interestOps() == SelectionKey.OP_READ;
        setLastUpdatedTime(timeMillis);
        
        SocketChannel clntChan = (SocketChannel) key.channel();
        long bytesRead;
        try {
            bytesRead = clntChan.read(recvBuffer);
            if (bytesRead == -1) {
                session.close();
            } else if (bytesRead == 0) {
                session.close();
            }
        } catch (Exception e) {
            Logger.error("Exception occur on {}", session, e);
            session.close();
        }
        
        recvBuffer.flip();
        try {
            if (!handleRequest(key)) {
                recvBuffer.clear();
            }
        } catch (Exception e) {
            Logger.error("Exception occur on {}", session, e);
            session.close();
        }
    }
    
    @Override
    public void callbackWrite(SelectionKey key, long timeMillis) {
        setLastUpdatedTime(timeMillis);
        
        SocketChannel clntChan = (SocketChannel) key.channel();
        try {
            while (sendBuffer.hasRemaining()) {
                clntChan.write(sendBuffer);
            }
        } catch (CharacterCodingException e) {
            session.close();
        } catch (IOException e) {
            session.close();
        }
        
        if (!handleRequest(key)) {
            recvBuffer.clear();
            assert key.interestOps() == SelectionKey.OP_WRITE;
            key.interestOps(SelectionKey.OP_READ);
        }
    }

    @Override
    public void callbackConnectError() {
        // Do nothing...
    }

    @Override
    public void setSession(Session session) {
        this.session = (ClientSession) session;
    }
    
    private boolean validCapacity() {
        return lineReader.length() < MAX_BUFFER_SIZE;
    }
    
    private boolean validRequest(String request) {
        return request.length() < MAX_BUFFER_SIZE;
    }
    
    private boolean handleRequest(final SelectionKey key) {
        String request;
        while (true) {
            request = lineReader.readLine(recvBuffer);
            if (request == null) {
                return false;
            }
            
            request = request.trim();
            String[] args = request.split("\\s+");
            if (request.length() != 0 && args.length != 0) {
                break;
            }
        }
        
        if (!validCapacity()) {
            Logger.error("Close client {}, due to recv-buffer size limit. length: {}, request: {}",
                    new Object[]{session, lineReader.length(), lineReader.subString(0, 128)});
            session.close();
            return false;
        }
        
        if (!validRequest(request)) {
            Logger.error("Close client {}, due to large request. length: {}, request: {}",
                    new Object[]{session, request.length(), request.substring(0, 128)});
            session.close();
            return false;
        }

        if (request.equals("quit")) {
            session.close();
            return false;
        }
        
        key.interestOps(key.interestOps()
                & (~(SelectionKey.OP_READ | SelectionKey.OP_WRITE)));

        commandExecutor.perform(request, new JobResultHandler(this));

        return true;
    }
    
    public class JobResultHandler implements CommandCallback {
        
        private ClientSessionHandler clnt;
        
        public JobResultHandler(ClientSessionHandler clnt) {
            this.clnt = clnt;
        }
        
        @Override
        public void callback(JobResult result) {
            try {
                clnt.setSendBuffer(charset.encode(formatter.get(result,
                        leaderElectionHandler.getCurrentLeaderHost())));
            } catch (Exception e) {
                Logger.error("Reply job result to client fail. {}", session, e);
                clnt.setSendBuffer(charset.encode(formatter.convert(e.toString())));
            } finally {
                slowLog(result);

                try {
                    session.getSelectionKey().interestOps(SelectionKey.OP_WRITE);
                } catch (Exception e) {
                    Logger.error("Register OP_WRITE to nio selector fail. {}", session, e);
                }
            }
        }

        private void slowLog(JobResult result) {
            Long start = (Long) result.getValue(CommonKey.START_TIME);
            Long end = (Long) result.getValue(CommonKey.END_TIME);
            String request = (String) result.getValue(CommonKey.REQUEST);
            List<String> reply = result.getMessages();

            try {
                Statistics.updateElapsedTimeForCommands(session.getRemoteHostIP(),
                        session.getRemoteHostPort(), request,
                        Arrays.toString(reply.toArray()), end - start,
                        config.getServerCommandSlowlog());
            } catch (Exception e) {
                Logger.error("Log slow command fail.", e);
            }
        }
    }

    public static class ReplyFormatter {

        public String get(JobResult result, String leader) {
            // If command not found.
            for (Throwable e : result.getExceptions()) {
                if (e instanceof MgmtCommandNotFoundException) {
                    if (LeaderState.isLeader()) {
                        return convert(EXCEPTIONMSG_COMMAND_NOT_FOUND,
                                ReplyType.NORMAL);
                    } else {
                        try {
                            return convert(leader, ReplyType.REDIRECT);
                        } catch (Exception e2) {
                            return convert(EXCEPTIONMSG_WRONG_NUMBER_ARGUMENTS);
                        }
                    }
                } else if (e instanceof MgmtCommandWrongArgumentException) {
                    // Send usage
                    return ((MgmtCommandWrongArgumentException) e).getUsage() + "\r\n";
                } else if (e instanceof InvocationTargetException) {
                    // Send usage
                    return convert("-" + e.toString(), ReplyType.NORMAL);
                }
            }
            
            // Reply message
            StringBuilder stringBuilder = new StringBuilder();
            for (String message : result.getMessages()) {
                if (message != null) {
                    stringBuilder.append(message).append(" ");
                }
            }
            
            if (stringBuilder.length() > 0) {
                return convert(
                        stringBuilder.substring(0, stringBuilder.length() - 1),
                        ReplyType.NORMAL);
            }
            
            // If message is null then there is a Exception
            for (Throwable e : result.getExceptions()) {
                stringBuilder.append(convertExceptionToReply(e)).append(" ");
            }

            if (stringBuilder.length() > 0) {
                return convert(
                        stringBuilder.substring(0, stringBuilder.length() - 1),
                        ReplyType.NORMAL);
            }
            
            // If there is any message to be sent...
            return convert(EXCEPTIONMSG_INTERNAL_ERROR, ReplyType.NORMAL);
        }

        public String convert(String reply) {
            return convert(reply, ReplyType.NORMAL);
        }

        public String convertExceptionToReply(Throwable e) {
            if (e instanceof KeeperException) {
                return EXCEPTIONMSG_ZOOKEEPER;
            } else if (e instanceof InterruptedException) {
                return EXCEPTIONMSG_INTERNAL_ERROR;
            } else if (e instanceof IOException) {
                return "-ERR Can not convert raw-data to json-format";
            } else {
                if (e.getMessage().startsWith(ERROR)) {
                    return e.getMessage();
                } else {
                    return String.format("-ERR %s", e.getMessage());
                }
            }
        }
        
        public String convert(String reply, ReplyType type) {
            if (reply == null) {
                return "\r\n";
            }
            
            if (reply.length() == 0) {
                return "\r\n";
            }
            
            if (reply.charAt(0) != '{' && reply.charAt(0) != '[') {
                reply = reply.replace("\"", "\\\"");
            }
            
            switch (type) {
            case NORMAL:
                switch (reply.charAt(0)) {
                case '{':
                    return "{\"state\":\"success\",\"data\":" + reply + "}\r\n";
                case '[':
                    return "{\"state\":\"success\",\"data\":" + reply + "}\r\n";
                case '+':
                    return "{\"state\":\"success\",\"msg\":\"" + reply + "\"}\r\n";
                case '-':
                    return "{\"state\":\"error\",\"msg\":\"" + reply + "\"}\r\n";
                default:
                    return reply + "\r\n";
                }
            case REDIRECT:
                return "{\"state\":\"redirect\",\"data\":" + reply + "}\r\n";
            default:
                return "Unkown exception";
            }
        }    

    }

    @Override
    public void setLastUpdatedTime(long timeMillis) {
        lastUpdatedTime = timeMillis;
    }
    
    @Override
    public long getLastUpdatedTime() {
        return lastUpdatedTime;
    }
    
    public void setSendBuffer(ByteBuffer buffer) {
        this.sendBuffer = buffer;
    }
    
}

