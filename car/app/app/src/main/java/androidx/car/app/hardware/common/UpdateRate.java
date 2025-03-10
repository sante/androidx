/*
 * Copyright 2021 The Android Open Source Project
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

package androidx.car.app.hardware.common;

import static androidx.annotation.RestrictTo.Scope.LIBRARY;

import androidx.annotation.IntDef;
import androidx.annotation.RestrictTo;
import androidx.car.app.annotations.CarProtocol;
import androidx.car.app.annotations.RequiresCarApi;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Defines the possible update rates that properties, sensors, and actions can be requested with
 * . */
@CarProtocol
@RequiresCarApi(3)
public final class UpdateRate {
    /**
     * Defines the possible update rates that properties, sensors, and actions can be requested
     * with.
     *
     * @hide
     */
    @IntDef({
            DEFAULT,
            UI,
            FASTEST,
    })
    @Retention(RetentionPolicy.SOURCE)
    @RestrictTo(LIBRARY)
    public @interface Value {
    }

    /**
     * Car hardware property, sensor, or action should be fetched at its default rate.
     */
    @Value
    public static final int DEFAULT = 0;

    /**
     * Car hardware property, sensor, or action should be fetched at a rate consistent with
     * drawing UI to a screen.
     */
    @Value
    public static final int UI = 1;

    /**
     * Car hardware property, sensor, or action should be fetched at its fastest possible rate.
     */
    @Value
    public static final int FASTEST = 2;

    private UpdateRate() {}
}
