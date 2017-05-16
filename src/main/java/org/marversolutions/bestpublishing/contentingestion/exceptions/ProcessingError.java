/*
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements.  See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License.  You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package org.marversolutions.bestpublishing.contentingestion.exceptions;

import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.commons.lang.builder.ToStringStyle;
import org.marversolutions.bestpublishing.constants.ProcessingErrorCode;

/**
 * Generic Best Publishing processing error, such as a content ZIP processing error.
 *
 * @author martin.bergljung@marversolutions.org
 */
public class ProcessingError {
    private ProcessingErrorCode errorCode;
    private String errorMsg;
    private Exception exception;

    public ProcessingError(ProcessingErrorCode errorCode, String errorMsg, Exception exception) {
        this.errorCode = errorCode;
        this.errorMsg = errorMsg;
        this.exception = exception;
    }

    public int getErrorCode() {
        return errorCode.getCode();
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public String getErrorDescription() {
        return errorCode.getDescription();
    }

    public String getErrorDetail() {
        if (exception != null) {
            if (exception.getMessage() != null) {
                return exception.getMessage();
            }

            return exception.getClass().getName();
        } else {
            return getErrorMsg();
        }
    }

    @Override
    public int hashCode() {
        return(new HashCodeBuilder()
                .append(errorCode)
                .append(errorMsg)
                .toHashCode());
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) { return false; }
        if (obj == this) { return true; }
        if (obj.getClass() != getClass()) {
            return false;
        }
        ProcessingError rhs = (ProcessingError) obj;
        return new EqualsBuilder()
                /** Note:
                 * Do not use appendSuper when the super class is java.lang.Object as
                 * default implementation of equals in Object class will return true only
                 * when two references are pointing to the same object instance and hence
                 * the effect is not desirable.
                 */
                //.appendSuper(super.equals(obj))
                .append(errorCode, rhs.errorCode)
                .append(errorMsg, rhs.errorMsg)
                .isEquals();
    }

    @Override
    public String toString() {
        return(new ToStringBuilder(this, ToStringStyle.SIMPLE_STYLE)
                .append("errorCode", errorCode)
                .append("errorMsg", errorMsg)
                .toString());
    }
}
