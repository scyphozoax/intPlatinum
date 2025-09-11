#!/usr/bin/env python
# -*- coding: utf-8 -*-

import socket
import threading
import json
import os
import time
import base64
import struct
import argparse

class ChatServer:
    # 定义服务器支持的客户端版本
    SUPPORTED_CLIENT_VERSION = "v1.0.0b"
    
    def __init__(self, host='0.0.0.0', port=7995):
        self.host = host
        self.port = port
        self.server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.clients = {}  # 存储用户名到套接字的映射
        self.user_ips = {}  # 存储用户名到IP地址的映射
        self.lock = threading.Lock()
    
    def validate_client_version(self, client_socket):
        """验证客户端版本是否兼容"""
        try:
            # 接收客户端版本信息
            version_data = client_socket.recv(1024).decode('utf-8')
            version_json = json.loads(version_data)
            encrypted_version = version_json.get('version')
            
            # 尝试解密base64编码的版本号
            try:
                client_version = base64.b64decode(encrypted_version).decode('utf-8')
            except Exception as e:
                print(f"版本号解密失败: {e}")
                return False, "版本号格式错误，无法解密"
            
            if not client_version:
                # 未提供版本信息，视为版本不兼容
                return False, "客户端未提供版本信息"
                
            # 检查版本是否兼容
            if client_version == self.SUPPORTED_CLIENT_VERSION:
                # 版本兼容，发送接受响应
                success_message = {
                    'type': 'version_accepted',
                    'content': '版本验证通过'
                }
                self.send_message_to_client(client_socket, success_message)
                return True, None
            else:
                # 版本不兼容，发送不兼容响应
                error_message = {
                    'type': 'version_mismatch',
                    'required_version': self.SUPPORTED_CLIENT_VERSION
                }
                self.send_message_to_client(client_socket, error_message)
                return False, f"客户端版本不兼容，需要版本 {self.SUPPORTED_CLIENT_VERSION}"
        except Exception as e:
            print(f"版本验证错误: {e}")
            return False, f"版本验证过程中发生错误: {e}"
        
    def start(self):
        try:
            self.server_socket.bind((self.host, self.port))
            self.server_socket.listen(5)
            print(f"服务器启动成功，监听 {self.host}:{self.port}")
            
            while True:
                client_socket, client_address = self.server_socket.accept()
                print(f"新连接：{client_address}")
                client_thread = threading.Thread(target=self.handle_client, args=(client_socket, client_address))
                client_thread.daemon = True
                client_thread.start()
                
        except Exception as e:
            print(f"服务器错误：{e}")
        finally:
            self.server_socket.close()
    
    def handle_client(self, client_socket, client_address):
        username = None
        try:
            # 先验证客户端版本
            is_valid_version, version_error = self.validate_client_version(client_socket)
            if not is_valid_version:
                print(f"客户端 {client_address} 版本验证失败: {version_error}")
                client_socket.close()
                return
                
            # 版本验证通过后，接收昵称
            username_data = client_socket.recv(1024).decode('utf-8')
            username_json = json.loads(username_data)
            username = username_json.get('username')
            
            if not username:
                client_socket.close()
                return
                
            # 检查昵称是否已存在
            with self.lock:
                if username in self.clients:
                    # 发送昵称重复错误消息给客户端
                    error_message = {
                        'type': 'error',
                        'content': '该昵称已被使用，请选择其他昵称'
                    }
                    self.send_message_to_client(client_socket, error_message)
                    client_socket.close()
                    return
                    
                # 添加到客户端列表和IP映射
                self.clients[username] = client_socket
                self.user_ips[username] = client_address[0]  # 保存用户IP地址
                
            # 发送连接成功确认消息
            success_message = {
                'type': 'connected',
                'content': '连接成功'
            }
            self.send_message_to_client(client_socket, success_message)
            
            # 广播新用户加入消息
            self.broadcast_system_message(f"{username} 加入了聊天室")
            
            # 发送当前在线用户列表
            self.send_user_list()
            
            # 处理客户端消息
            while True:
                header_data = client_socket.recv(4)
                if not header_data:
                    break
                    
                msg_len = struct.unpack('!I', header_data)[0]
                data = self.recv_all(client_socket, msg_len)
                
                if not data:
                    break
                    
                message = json.loads(data.decode('utf-8'))
                msg_type = message.get('type')
                
                if msg_type == 'text':
                    self.broadcast_message(message, username)
                elif msg_type == 'file':
                    self.broadcast_file(message, username)
                elif msg_type == 'disconnect':
                    # 收到客户端主动断开连接的请求
                    # 不需要做特别处理，让finally块处理断开逻辑
                    break
                    
        except Exception as e:
            # 忽略客户端正常断开连接时的错误
            error_str = str(e)
            if "远程主机强迫关闭了一个现有的连接" not in error_str and "[WinError 10053]" not in error_str:
                print(f"处理客户端 {client_address} 错误：{e}")
        finally:
            # 客户端断开连接
            if username and username in self.clients:
                with self.lock:
                    del self.clients[username]
                    if username in self.user_ips:
                        del self.user_ips[username]
                client_socket.close()
                self.broadcast_system_message(f"{username} 离开了聊天室")
                self.send_user_list()
    
    def recv_all(self, sock, n):
        data = b''
        while len(data) < n:
            packet = sock.recv(n - len(data))
            if not packet:
                return None
            data += packet
        return data
    
    def send_message_to_client(self, client_socket, message):
        message['timestamp'] = time.time()
        
        msg_json = json.dumps(message)
        msg_bytes = msg_json.encode('utf-8')
        header = struct.pack('!I', len(msg_bytes))
        
        try:
            client_socket.sendall(header + msg_bytes)
        except:
            pass
            
    def broadcast_message(self, message, sender):
        message['sender'] = sender
        message['timestamp'] = time.time()
        
        msg_json = json.dumps(message)
        msg_bytes = msg_json.encode('utf-8')
        header = struct.pack('!I', len(msg_bytes))
        
        with self.lock:
            for username, client in self.clients.items():
                try:
                    client.sendall(header + msg_bytes)
                except:
                    pass
    
    def broadcast_file(self, message, sender):
        message['sender'] = sender
        message['timestamp'] = time.time()
        
        msg_json = json.dumps(message)
        msg_bytes = msg_json.encode('utf-8')
        header = struct.pack('!I', len(msg_bytes))
        
        with self.lock:
            for username, client in self.clients.items():
                try:
                    client.sendall(header + msg_bytes)
                except:
                    pass
    
    def broadcast_system_message(self, message_text):
        message = {
            'type': 'system',
            'content': message_text,
            'timestamp': time.time()
        }
        
        msg_json = json.dumps(message)
        msg_bytes = msg_json.encode('utf-8')
        header = struct.pack('!I', len(msg_bytes))
        
        with self.lock:
            for username, client in self.clients.items():
                try:
                    client.sendall(header + msg_bytes)
                except:
                    pass
    
    def send_user_list(self):
        with self.lock:
            # 构建包含IP地址的用户信息列表
            users_with_ip = []
            for username in self.clients.keys():
                users_with_ip.append({
                    'username': username,
                    'ip': self.user_ips.get(username, '未知')
                })
            
        message = {
            'type': 'user_list',
            'users': users_with_ip,
            'timestamp': time.time()
        }
        
        msg_json = json.dumps(message)
        msg_bytes = msg_json.encode('utf-8')
        header = struct.pack('!I', len(msg_bytes))
        
        with self.lock:
            for username, client in self.clients.items():
                try:
                    client.sendall(header + msg_bytes)
                except:
                    pass

if __name__ == "__main__":
    # 创建命令行参数解析器
    parser = argparse.ArgumentParser(description='intPlatinum 聊天服务器')
    parser.add_argument('--host', type=str, default='0.0.0.0', help='服务器绑定的主机地址 (默认: 0.0.0.0)')
    parser.add_argument('--port', type=int, default=7995, help='服务器绑定的端口号 (默认: 7995)')
    
    # 解析命令行参数
    args = parser.parse_args()
    
    # 启动服务器，使用解析的主机和端口
    server = ChatServer(host=args.host, port=args.port)
    server.start()