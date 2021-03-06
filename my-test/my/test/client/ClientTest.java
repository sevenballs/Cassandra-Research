/*
 * Copyright 2011 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package my.test.client;

import org.apache.cassandra.transport.Client;

//加-javaagent:"E:/cassandra/lib/jamm-0.2.5.jar"
public class ClientTest {

    //提示>>后，
	//先输入: startup lz4
	//authenticate username=cassandra password=cassandra
	//register status_change
	//query SELECT schema_version FROM system.local WHERE key='local'
	//或 query SELECT schema_version FROM system.local WHERE key='local' !10 (感叹号是分隔符，后面是pageSize，感叹号跟数字之间没有空格)
    public static void main(String[] args) {
        try {
            Client.main(new String[] { "localhost", "9042" });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
