/*******************************************************************************
 * Copyright (c) 2014, MD PnP Program
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************/
package org.mdpnp.devices.philips.intellivue.data;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.mdpnp.devices.io.util.Bits;

/**
 * @author Jeff Plourde
 *
 */
public class VisualGrid implements Value {
    public static class Entry implements Value {
        private final Float absoluteValue = new Float();
        private int scaledValue, level;

        @Override
        public void format(ByteBuffer bb) {
            absoluteValue.format(bb);
            Bits.putUnsignedShort(bb, scaledValue);
            Bits.putUnsignedShort(bb, level);
        }

        @Override
        public void parse(ByteBuffer bb) {
            absoluteValue.parse(bb);
            scaledValue = Bits.getUnsignedShort(bb);
            level = Bits.getUnsignedShort(bb);
        }

        @Override
        public java.lang.String toString() {
            return "[absoluteValue=" + absoluteValue + ",scaledValue=" + scaledValue + ",level=" + level + "]";
        }

    }

    private final List<Entry> list = new ArrayList<Entry>();

    @Override
    public void format(ByteBuffer bb) {
        Bits.putUnsignedShort(bb, list.size());
        bb.mark();
        Bits.putUnsignedShort(bb, 0);
        int pos = bb.position();
        for (Entry e : list) {
            e.format(bb);
        }
        int length = bb.position() - pos;
        bb.reset();
        Bits.putUnsignedShort(bb, length);
        bb.position(bb.position() + length);
    }

    @Override
    public void parse(ByteBuffer bb) {
        int count = Bits.getUnsignedShort(bb);
        @SuppressWarnings("unused")
        int length = Bits.getUnsignedShort(bb);
        list.clear();
        for (int i = 0; i < count; i++) {
            Entry e = new Entry();
            e.parse(bb);
            list.add(e);
        }
    }

    @Override
    public java.lang.String toString() {
        return list.toString();
    }
}
