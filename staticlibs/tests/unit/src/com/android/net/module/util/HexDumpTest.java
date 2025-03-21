/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.net.module.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class HexDumpTest {
    @Test
    public void testBytesToHexString() {
        assertEquals("abcdef", HexDump.toHexString(
                new byte[]{(byte) 0xab, (byte) 0xcd, (byte) 0xef}, false));
        assertEquals("ABCDEF", HexDump.toHexString(
                new byte[]{(byte) 0xab, (byte) 0xcd, (byte) 0xef}, true));
    }

    @Test
    public void testNullArray() {
        assertEquals("(null)", HexDump.dumpHexString(null));
    }

    @Test
    public void testHexStringToByteArray() {
        assertArrayEquals(new byte[]{(byte) 0xab, (byte) 0xcd, (byte) 0xef},
                HexDump.hexStringToByteArray("abcdef"));
        assertArrayEquals(new byte[]{(byte) 0xAB, (byte) 0xCD, (byte) 0xEF},
                HexDump.hexStringToByteArray("ABCDEF"));
    }

    @Test
    public void testInvalidHexStringToByteArray() {
        assertThrows(IllegalArgumentException.class, () -> HexDump.hexStringToByteArray("abxX"));
    }

    @Test
    public void testIntegerToByteArray() {
        assertArrayEquals(new byte[]{(byte) 0xff, (byte) 0x00, (byte) 0x00, (byte) 0x04},
                HexDump.toByteArray((int) 0xff000004));
    }

    @Test
    public void testByteToByteArray() {
        assertArrayEquals(new byte[]{(byte) 0x7f}, HexDump.toByteArray((byte) 0x7f));
    }

    @Test
    public void testIntegerToHexString() {
        assertEquals("FF000004", HexDump.toHexString((int) 0xff000004));
    }

    @Test
    public void testByteToHexString() {
        assertEquals("7F", HexDump.toHexString((byte) 0x7f));
    }
}
