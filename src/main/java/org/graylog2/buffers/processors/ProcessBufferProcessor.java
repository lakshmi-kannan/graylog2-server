/**
 * Copyright 2012 Lennart Koopmann <lennart@socketfeed.com>
 *
 * This file is part of Graylog2.
 *
 * Graylog2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Graylog2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Graylog2.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.graylog2.buffers.processors;

import com.lmax.disruptor.EventHandler;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.TimerContext;
import java.util.concurrent.TimeUnit;
import org.apache.log4j.Logger;
import org.graylog2.Core;
import org.graylog2.buffers.LogMessageEvent;
import org.graylog2.logmessage.LogMessageImpl;
import org.graylog2.plugin.filters.MessageFilter;
import org.graylog2.plugin.logmessage.LogMessage;

/**
 * @author Lennart Koopmann <lennart@socketfeed.com>
 */
public class ProcessBufferProcessor implements EventHandler<LogMessageEvent> {

    private static final Logger LOG = Logger.getLogger(ProcessBufferProcessor.class);

    private Core server;

    public ProcessBufferProcessor(Core server) {
        this.server = server;
    }

    @Override
    public void onEvent(LogMessageEvent event, long sequence, boolean endOfBatch) throws Exception {
        Metrics.newMeter(ProcessBufferProcessor.class, "IncomingMessages", "messages", TimeUnit.SECONDS).mark();
        Metrics.newMeter(ProcessBufferProcessor.class, "IncomingMessagesMinutely", "messages", TimeUnit.MINUTES).mark();
        TimerContext tcx = Metrics.newTimer(ProcessBufferProcessor.class, "ProcessTime", TimeUnit.MICROSECONDS, TimeUnit.SECONDS).time();

        LogMessage msg = event.getMessage();

        LOG.debug("Starting to process message <" + msg.getId() + ">.");

        for (Class<? extends MessageFilter> filterType : server.getFilters()) {
            try {
                // Always create a new instance of this filter.
                MessageFilter filter = filterType.newInstance();

                String name = filterType.getSimpleName();
                LOG.debug("Applying filter [" + name +"] on message <" + msg.getId() + ">.");

                filter.filter(msg, server);
                if (filter.discard()) {
                    LOG.debug("Filter [" + name + "] marked message <" + msg.getId() + "> to be discarded. Dropping message.");
                    Metrics.newMeter(ProcessBufferProcessor.class, "FilteredOutMessages", "messages", TimeUnit.SECONDS).mark();
                    return;
                }
            } catch (Exception e) {
                LOG.error("Could not apply filter [" + filterType.getSimpleName() +"] on message <" + msg.getId() +">: ", e);
            }
        }

        LOG.debug("Finished processing message. Writing to output buffer.");
        Metrics.newMeter(ProcessBufferProcessor.class, "OutgoingMessages", "messages", TimeUnit.SECONDS).mark();
        server.getOutputBuffer().insert(msg);
        tcx.stop();
    }

}
