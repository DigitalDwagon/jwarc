/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (C) 2018 National Library of Australia and the jwarc contributors
 */

package org.netpreserve.jwarc;

import org.netpreserve.jwarc.lowlevel.HeaderName;

import java.util.Map;

class WarcResponseImpl extends WarcRecordImpl implements WarcResponse {
    WarcResponseImpl(Map<HeaderName, String> headers) {
        super(headers);
    }
}
