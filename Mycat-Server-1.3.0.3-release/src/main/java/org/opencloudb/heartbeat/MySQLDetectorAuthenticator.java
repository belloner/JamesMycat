/*
 * Copyright (c) 2013, OpenCloudDB/MyCAT and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software;Designed and Developed mainly by many Chinese 
 * opensource volunteers. you can redistribute it and/or modify it under the 
 * terms of the GNU General Public License version 2 only, as published by the
 * Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 * 
 * Any questions about this component can be directed to it's project Web address 
 * https://code.google.com/p/opencloudb/.
 *
 */
package org.opencloudb.heartbeat;

import org.opencloudb.mysql.CharsetUtil;
import org.opencloudb.mysql.SecurityUtil;
import org.opencloudb.net.ConnectionException;
import org.opencloudb.net.NIOHandler;
import org.opencloudb.net.mysql.EOFPacket;
import org.opencloudb.net.mysql.ErrorPacket;
import org.opencloudb.net.mysql.HandshakePacket;
import org.opencloudb.net.mysql.OkPacket;
import org.opencloudb.net.mysql.Reply323Packet;

/**
 * @author mycat
 */
public class MySQLDetectorAuthenticator implements NIOHandler {

	private final MySQLDetector source;

	public MySQLDetectorAuthenticator(MySQLDetector source) {
		this.source = source;
	}

	@Override
	public void handle(byte[] data) {
		MySQLDetector source = this.source;
		switch (data[4]) {
		case OkPacket.FIELD_COUNT:
			HandshakePacket packet = source.getHandshake();
			if (packet == null) {
				processHandshakePackage(data, source);
				// 发送认证数据包
				source.authenticate();
				return;
			}
			source.setHandler(new MySQLDetectorHandler(source));
			source.setAuthenticated(true);
			source.heartbeat();// 成功后发起心跳。
			break;
		case ErrorPacket.FIELD_COUNT:
			ErrorPacket err = new ErrorPacket();
			err.read(data);
			String errMsg = new String(err.message);
			source.close(errMsg);
			throw new ConnectionException(err.errno, errMsg);
		case EOFPacket.FIELD_COUNT:
			auth323(data[3]);
			break;
		default:
			packet = source.getHandshake();
			if (packet == null) {
				processHandshakePackage(data, source);
				// 发送认证数据包
				source.authenticate();
				break;
			} else {
				throw new RuntimeException("Unknown packet");
			}
		}
	}

	private void processHandshakePackage(byte[] data, MySQLDetector source) {
		HandshakePacket hsp;
		// 设置握手数据包
		hsp = new HandshakePacket();
		hsp.read(data);
		source.setHandshake(hsp);

		// 设置字符集编码
		int charsetIndex = (hsp.serverCharsetIndex & 0xff);
		String charset = CharsetUtil.getCharset(charsetIndex);
		if (charset != null) {
			source.setCharsetIndex(charsetIndex);
			source.setCharset(charset);
		} else {
			throw new RuntimeException("Unknown charsetIndex:" + charsetIndex);
		}
	}

	/**
	 * 发送323响应认证数据包
	 */
	private void auth323(byte packetId) {
		// 发送323响应认证数据包
		Reply323Packet r323 = new Reply323Packet();
		r323.packetId = ++packetId;
		String pass = source.getPassword();
		if (pass != null && pass.length() > 0) {
			byte[] seed = source.getHandshake().seed;
			r323.seed = SecurityUtil.scramble323(pass, new String(seed))
					.getBytes();
		}
		r323.write(source);
	}

}