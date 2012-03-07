/*
 * Copyright 2010-2012 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.ning.arecibo.alert.confdata.enums;

public enum ManagingKeyActionType
{

    NO_ACTION(0),
    QUIESCE(1),
    DISABLE(2);

    private final int level;

    private ManagingKeyActionType(final int level)
    {
        this.level = level;
    }

    public int getLevel()
    {
        return this.level;
    }
}
