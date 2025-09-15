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
import sys
import signal
import atexit

class ChatServer:
    # 定义服务器支持的客户端版本列表
    SUPPORTED_CLIENT_VERSIONS = ["v1.0.2a","v1.0.1a-mv"]
    SERVER_VERSION = "v1.0.2a"  # 服务器版本
    
    def __init__(self, host='0.0.0.0', port=7995):
        self.host = host
        self.port = port
        self.server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.clients = {}  # 存储用户名到套接字的映射
        self.user_ips = {}  # 存储用户名到IP地址的映射
        self.banned_ips = set()  # 存储被禁止的IP地址
        # 使用绝对路径确保跨平台兼容性
        self.banned_ips_file = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'banned_ips.json')
        self.clients_lock = threading.Lock()
        
        # 加载黑名单
        self._load_banned_ips()
        self.advertise_thread = None  # 广告线程
        self.advertise_stop_event = threading.Event()  # 广告停止事件
        self.shutdown_requested = False  # 关闭请求标志
        self.running = True  # 服务器运行状态
        
        # 服务器端命令输入线程
        self.command_thread = None
        self.command_running = False
    
    def _load_banned_ips(self):
        """从JSON文件加载黑名单"""
        try:
            if os.path.exists(self.banned_ips_file):
                with open(self.banned_ips_file, 'r', encoding='utf-8') as f:
                    banned_list = json.load(f)
                    self.banned_ips = set(banned_list)
                    print(f"✅ 已加载 {len(self.banned_ips)} 个被禁止的IP地址")
            else:
                # 创建空的黑名单文件
                self._save_banned_ips()
                print("✅ 已创建新的黑名单文件")
        except Exception as e:
            print(f"❌ 加载黑名单文件失败: {e}")
            self.banned_ips = set()
    
    def _save_banned_ips(self):
        """保存黑名单到JSON文件"""
        try:
            with open(self.banned_ips_file, 'w', encoding='utf-8') as f:
                json.dump(list(self.banned_ips), f, ensure_ascii=False, indent=2)
        except Exception as e:
            print(f"❌ 保存黑名单文件失败: {e}")
    
    def validate_client_version(self, client_socket):
        """验证客户端版本是否兼容"""
        try:
            # 接收消息长度（4字节）
            length_data = self.recv_all(client_socket, 4)
            if not length_data:
                return False, "无法接收消息长度"
            
            message_length = struct.unpack('!I', length_data)[0]
            
            # 接收版本信息消息内容
            version_data = self.recv_all(client_socket, message_length)
            if not version_data:
                return False, "无法接收版本信息"
            
            version_json = json.loads(version_data.decode('utf-8'))
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
            if client_version in self.SUPPORTED_CLIENT_VERSIONS:
                # 版本兼容，发送接受响应
                success_message = {
                    'type': 'version_accepted',
                    'content': f'版本验证通过 ({client_version})'
                }
                if self.send_message_to_client(client_socket, success_message):
                    print(f"客户端版本验证成功: {client_version}")
                    return True, None
                else:
                    print(f"发送版本接受消息失败，关闭连接")
                    return False, "发送版本接受消息失败"
            else:
                # 版本不兼容，发送不兼容响应
                error_message = {
                    'type': 'version_mismatch',
                    'content': f"客户端版本不兼容，支持的版本: {', '.join(self.SUPPORTED_CLIENT_VERSIONS)}",
                    'supported_versions': self.SUPPORTED_CLIENT_VERSIONS
                }
                self.send_message_to_client(client_socket, error_message)
                return False, f"客户端版本不兼容，支持的版本: {', '.join(self.SUPPORTED_CLIENT_VERSIONS)}"
        except ConnectionResetError as e:
            print(f"版本验证错误: 客户端重置连接 - {e}")
            return False, f"客户端重置连接: {e}"
        except socket.error as e:
            print(f"版本验证错误: Socket网络错误 - {e}")
            return False, f"网络连接错误: {e}"
        except json.JSONDecodeError as e:
            print(f"版本验证错误: JSON解析失败 - {e}")
            return False, f"版本信息格式错误: {e}"
        except Exception as e:
            print(f"版本验证错误: 未知错误 - {e}")
            return False, f"版本验证过程中发生错误: {e}"
        
    def start(self):
        # 注册信号处理器
        signal.signal(signal.SIGINT, self._signal_handler)
        # SIGTERM在Windows上可能不可用
        if hasattr(signal, 'SIGTERM'):
            signal.signal(signal.SIGTERM, self._signal_handler)
        atexit.register(self.graceful_shutdown)
        
        try:
            self.server_socket.bind((self.host, self.port))
            self.server_socket.listen(5)
            print(f"服务器启动成功，监听 {self.host}:{self.port}")
            print("="*60)
            print("   输入 'help' 显示所有可用命令")
            print("   按下 Ctrl+C 或输入 'shutdown' 停止服务器")
            print("   服务器日志将在下方显示")
            print("="*60)
            
            # 启动服务器端命令输入线程
            self.command_running = True
            self.command_thread = threading.Thread(target=self._command_input_worker, daemon=True)
            self.command_thread.start()
            
            while self.running:
                try:
                    self.server_socket.settimeout(1.0)  # 设置超时以便检查running状态
                    client_socket, client_address = self.server_socket.accept()
                    print(f"新连接：{client_address} - Socket: {client_socket.fileno()}")
                    
                    # 为每个客户端连接创建独立的线程，避免阻塞主循环
                    client_thread = threading.Thread(
                        target=self.handle_client, 
                        args=(client_socket, client_address),
                        daemon=True,
                        name=f"Client-{client_address[0]}:{client_address[1]}"
                    )
                    client_thread.start()
                    
                except socket.timeout:
                    continue  # 超时后继续检查running状态
                except OSError as e:
                    if self.running:
                        print(f"接受连接时发生错误: {e}")
                        # 短暂延迟后继续，避免快速循环
                        time.sleep(0.1)
                        continue
                    break
                except Exception as e:
                    if self.running:
                        print(f"处理新连接时发生未知错误: {e}")
                        time.sleep(0.1)
                        continue
                    break
                
        except Exception as e:
            if self.running:
                print(f"服务器错误：{e}")
        finally:
            self.graceful_shutdown()
    
    def handle_client(self, client_socket, client_address):
        username = None
        client_ip = client_address[0]
        print(f"开始处理客户端 {client_address} - Socket: {client_socket.fileno()}")
        
        # 检查IP是否被禁止
        if client_ip in self.banned_ips:
            print(f"拒绝被禁止的IP {client_ip} 的连接")
            
            # 在独立的线程中处理被封禁IP，避免影响主线程
            def handle_banned_ip():
                try:
                    banned_message = {
                        "type": "banned",
                        "content": "您的IP地址已被该服务器封禁",
                        "timestamp": int(time.time())
                    }
                    self.send_message_to_client(client_socket, banned_message)
                    # 给客户端一点时间接收消息
                    time.sleep(0.05)
                except Exception as e:
                    print(f"向被封禁IP {client_ip} 发送消息失败: {e}")
                finally:
                    # 安全关闭socket连接
                    try:
                        client_socket.shutdown(socket.SHUT_RDWR)
                    except Exception:
                        pass  # 忽略shutdown错误
                    
                    try:
                        client_socket.close()
                    except Exception:
                        pass  # 忽略close错误
                    
                    print(f"已关闭被封禁IP {client_ip} 的连接")
            
            # 在独立线程中处理，避免阻塞
            banned_thread = threading.Thread(target=handle_banned_ip, daemon=True)
            banned_thread.start()
            return
        
        try:
            # 先验证客户端版本
            print(f"正在验证客户端 {client_address} 的版本...")
            is_valid_version, version_error = self.validate_client_version(client_socket)
            if not is_valid_version:
                print(f"客户端 {client_address} 版本验证失败: {version_error}")
                client_socket.close()
                return
            print(f"客户端 {client_address} 版本验证成功")
                
            # 版本验证通过后，接收昵称
            # 接收消息长度（4字节）
            length_data = self.recv_all(client_socket, 4)
            if not length_data:
                client_socket.close()
                return
            
            message_length = struct.unpack('!I', length_data)[0]
            
            # 接收用户名消息内容
            username_data = self.recv_all(client_socket, message_length)
            if not username_data:
                client_socket.close()
                return
            
            username_json = json.loads(username_data.decode('utf-8'))
            username = username_json.get('username')
            
            if not username:
                client_socket.close()
                return
                
            # 检查昵称是否已存在
            with self.clients_lock:
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
            if not self.send_message_to_client(client_socket, success_message):
                print(f"发送连接成功消息失败，关闭连接")
                with self.clients_lock:
                    if username in self.clients:
                        del self.clients[username]
                    if username in self.user_ips:
                        del self.user_ips[username]
                client_socket.close()
                return
            
            print(f"客户端 {username} 连接成功确认消息已发送")
            
            # 广播新用户加入消息
            self.broadcast_system_message(f"{username} 加入了聊天室")
            
            # 发送当前在线用户列表
            self.send_user_list()
            
            print(f"用户列表已发送给 {username}")
            
            # 处理客户端消息
            while True:
                try:
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
                        # 直接广播文本消息
                        self.broadcast_message(message, username)
                    elif msg_type == 'file':
                        self.broadcast_file(message, username)
                    elif msg_type == 'heartbeat':
                        # 处理心跳包，发送pong响应
                        pong_message = {
                            'type': 'pong',
                            'content': 'pong',
                            'timestamp': int(time.time() * 1000)
                        }
                        self.send_message_to_client(client_socket, pong_message)
                        print(f"收到来自 {username} 的心跳包，已回复pong")
                    elif msg_type == 'disconnect':
                        # 收到客户端主动断开连接的请求
                        # 不需要做特别处理，让finally块处理断开逻辑
                        break
                except (ConnectionResetError, socket.error, OSError) as e:
                    # 客户端连接异常，正常断开
                    print(f"客户端 {username or client_address} 连接异常断开: {e}")
                    break
                except Exception as e:
                    # 其他异常，记录但不影响服务器运行
                    print(f"处理客户端 {username or client_address} 时发生错误: {e}")
                    break
                    
        except Exception as e:
            # 忽略客户端正常断开连接时的错误
            error_str = str(e)
            if "远程主机强迫关闭了一个现有的连接" not in error_str and "[WinError 10053]" not in error_str:
                print(f"处理客户端 {client_address} 错误：{e}")
        finally:
            # 客户端断开连接
            if username and username in self.clients:
                with self.clients_lock:
                    del self.clients[username]
                    if username in self.user_ips:
                        del self.user_ips[username]
                
                # 安全关闭socket
                try:
                    client_socket.shutdown(socket.SHUT_RDWR)
                except Exception:
                    pass
                
                try:
                    client_socket.close()
                except Exception:
                    pass
                
                self.broadcast_system_message(f"{username} 离开了聊天室")
                self.send_user_list()
    
    def recv_all(self, sock, n):
        data = b''
        while len(data) < n:
            try:
                packet = sock.recv(n - len(data))
                if not packet:
                    # 连接正常关闭，不需要打印错误信息
                    return None
                data += packet
            except ConnectionResetError:
                # 连接被客户端重置，这是正常的断开连接情况
                return None
            except socket.error as e:
                # 只在非正常关闭的情况下打印错误信息
                error_msg = str(e)
                if "Bad file descriptor" not in error_msg and "[Errno 9]" not in error_msg:
                    print(f"recv_all: Socket错误 - {e}，已接收 {len(data)}/{n} 字节")
                return None
            except Exception as e:
                print(f"recv_all: 未知错误 - {e}，已接收 {len(data)}/{n} 字节")
                return None
        return data
    
    def send_message_to_client(self, client_socket, message):
        message['timestamp'] = int(time.time() * 1000)  # 转换为毫秒时间戳整数
        
        msg_json = json.dumps(message)
        msg_bytes = msg_json.encode('utf-8')
        header = struct.pack('!I', len(msg_bytes))
        
        try:
            client_socket.sendall(header + msg_bytes)
            print(f"消息发送成功: {message.get('type', 'unknown')} - {len(msg_bytes)} bytes")
        except Exception as e:
            print(f"发送消息失败: {e}")
            return False
        return True
            
    def broadcast_message(self, message, sender):
        message['sender'] = sender
        message['timestamp'] = int(time.time() * 1000)  # 转换为毫秒时间戳整数
        
        msg_json = json.dumps(message)
        msg_bytes = msg_json.encode('utf-8')
        header = struct.pack('!I', len(msg_bytes))
        
        with self.clients_lock:
            for username, client in self.clients.items():
                try:
                    client.sendall(header + msg_bytes)
                except:
                    pass
    
    def _signal_handler(self, signum, frame):
        """信号处理器"""
        signal_names = {signal.SIGINT: 'SIGINT', signal.SIGTERM: 'SIGTERM'} if hasattr(signal, 'SIGTERM') else {signal.SIGINT: 'SIGINT'}
        signal_name = signal_names.get(signum, str(signum))
        print(f"\n收到信号 {signal_name} ({signum})，正在关闭服务器...")
        
        # 设置关闭标志
        self.running = False
        self.command_running = False
        
        # 在新线程中执行关闭操作，避免信号处理器阻塞
        shutdown_thread = threading.Thread(target=self.graceful_shutdown, daemon=True)
        shutdown_thread.start()
    
    def graceful_shutdown(self):
        """关闭服务器"""
        # 防止重复调用
        if hasattr(self, '_shutdown_in_progress') and self._shutdown_in_progress:
            return
        self._shutdown_in_progress = True
        
        print("开始关闭服务器...")
        self.running = False
        self.command_running = False
        
        try:
            # 停止广告线程
            if self.advertise_thread and self.advertise_thread.is_alive():
                self.advertise_stop_event.set()
                self.advertise_thread.join(timeout=2)
            
            # 向所有客户端发送服务器关闭消息
            try:
                shutdown_message = {
                    "type": "server_shutdown",
                    "content": "服务器已关闭",
                    "timestamp": int(time.time())
                }
                
                with self.clients_lock:
                    for username, client_socket in list(self.clients.items()):
                        try:
                            self.send_message_to_client(client_socket, shutdown_message)
                        except Exception as e:
                            print(f"向客户端 {username} 发送关闭消息失败: {e}")
                
                # 给客户端时间接收消息
                time.sleep(0.5)
            except Exception as e:
                print(f"发送关闭消息时出错: {e}")
            
            # 关闭所有客户端连接
            with self.clients_lock:
                for username, client_socket in list(self.clients.items()):
                    try:
                        client_socket.close()
                    except:
                        pass
                self.clients.clear()
                self.user_ips.clear()
            
            # 关闭服务器套接字
            try:
                self.server_socket.close()
            except:
                pass
            
            print("服务器已关闭")
            
        except Exception as e:
            print(f"关闭服务器时出错: {e}")
        finally:
            # 在Linux环境下，确保进程能够正常退出
            if os.name == 'posix':
                os._exit(0)
    
    def broadcast_file(self, message, sender):
        message['sender'] = sender
        message['timestamp'] = int(time.time() * 1000)  # 转换为毫秒时间戳整数
        
        msg_json = json.dumps(message)
        msg_bytes = msg_json.encode('utf-8')
        header = struct.pack('!I', len(msg_bytes))
        
        with self.clients_lock:
            for username, client in self.clients.items():
                try:
                    client.sendall(header + msg_bytes)
                except:
                    pass
    
    def broadcast_system_message(self, message_text):
        message = {
            'type': 'system',
            'content': message_text,
            'timestamp': int(time.time() * 1000)  # 转换为毫秒时间戳整数
        }
        
        msg_json = json.dumps(message)
        msg_bytes = msg_json.encode('utf-8')
        header = struct.pack('!I', len(msg_bytes))
        
        with self.clients_lock:
            for username, client in self.clients.items():
                try:
                    client.sendall(header + msg_bytes)
                except:
                    pass
    
    def _send_popup_message_to_ip(self, target_ip, message_content):
        """向指定IP发送弹窗消息"""
        message = {
            'type': 'popup_message',
            'content': message_content,
            'timestamp': int(time.time() * 1000)
        }
        
        msg_json = json.dumps(message)
        msg_bytes = msg_json.encode('utf-8')
        header = struct.pack('!I', len(msg_bytes))
        
        sent = False
        with self.clients_lock:
            for username, client_socket in self.clients.items():
                if self.user_ips.get(username) == target_ip:
                    try:
                        client_socket.sendall(header + msg_bytes)
                        print(f"✅ 弹窗消息已发送给 {target_ip} (用户: {username}): {message_content}")
                        sent = True
                    except Exception as e:
                        print(f"❌ 发送弹窗消息失败 {target_ip}: {e}")
        
        if not sent:
            print(f"❌ 未找到IP地址为 {target_ip} 的在线用户")
    
    def _send_popup_announcement(self, announcement_content):
        """发送弹窗公告给所有用户"""
        message = {
            'type': 'popup_announcement',
            'content': announcement_content,
            'timestamp': int(time.time() * 1000)
        }
        
        msg_json = json.dumps(message)
        msg_bytes = msg_json.encode('utf-8')
        header = struct.pack('!I', len(msg_bytes))
        
        sent_count = 0
        with self.clients_lock:
            for username, client_socket in self.clients.items():
                try:
                    client_socket.sendall(header + msg_bytes)
                    sent_count += 1
                except Exception as e:
                    print(f"❌ 发送弹窗公告失败给用户 {username}: {e}")
        
        print(f"✅ 弹窗公告已发送给 {sent_count} 个用户: {announcement_content}")
    
    def _command_input_worker(self):
        """服务器端命令输入工作线程"""
        stdin_error_count = 0  # 记录连续错误次数
        last_stdin_check = time.time()
        
        while self.command_running and self.running:
            try:
                # 在Linux环境下，使用非阻塞输入检查
                if os.name == 'posix':
                    import select
                    import sys
                    
                    # 定期检查stdin状态（每10秒）
                    current_time = time.time()
                    if current_time - last_stdin_check > 10:
                        try:
                            # 尝试刷新stdin缓冲区
                            sys.stdin.flush()
                            # 重置错误计数
                            if stdin_error_count > 0:
                                print("stdin状态已恢复正常")
                                stdin_error_count = 0
                        except:
                            pass
                        last_stdin_check = current_time
                    
                    try:
                        # 检查stdin是否可用
                        if not sys.stdin.closed and sys.stdin.readable():
                            # 检查是否有输入可用（超时0.5秒）
                            ready, _, _ = select.select([sys.stdin], [], [], 0.5)
                            if ready:
                                try:
                                    command = input().strip()
                                    if command:
                                        self._handle_server_command(command)
                                        stdin_error_count = 0  # 成功处理命令，重置错误计数
                                except EOFError:
                                    # stdin被关闭，尝试重新打开
                                    stdin_error_count += 1
                                    print(f"检测到stdin异常（第{stdin_error_count}次），正在尝试恢复...")
                                    time.sleep(0.5)
                                    continue
                            else:
                                continue  # 没有输入，继续循环
                        else:
                            # stdin不可用，等待一段时间后重试
                            stdin_error_count += 1
                            if stdin_error_count <= 3:  # 只在前几次错误时打印
                                print(f"stdin不可用（第{stdin_error_count}次），等待恢复...")
                            time.sleep(0.5)
                            continue
                    except (KeyboardInterrupt):
                        # 重新抛出这些异常，让外层处理
                        raise
                    except Exception as select_error:
                        # Linux下的select或input错误，记录但继续运行
                        stdin_error_count += 1
                        if stdin_error_count <= 3:  # 只在前几次错误时打印详细信息
                            print(f"Linux输入处理错误（第{stdin_error_count}次）: {select_error}")
                            print("正在重置输入状态，请重新输入命令...")
                        
                        # 尝试刷新stdin缓冲区
                        try:
                            sys.stdin.flush()
                        except:
                            pass
                        
                        # 根据错误次数调整延迟时间
                        delay = min(0.5 + (stdin_error_count * 0.1), 2.0)
                        time.sleep(delay)
                        continue
                else:
                    # Windows环境下使用改进的输入处理
                    try:
                        command = input().strip()
                        if command:
                            self._handle_server_command(command)
                    except (EOFError, KeyboardInterrupt):
                        # 重新抛出这些异常，让外层处理
                        raise
                    except Exception as input_error:
                        # Windows下的输入错误，记录但继续运行
                        print(f"输入处理错误: {input_error}")
                        print("请重新输入命令...")
                        continue
            except (EOFError, KeyboardInterrupt):
                print("\n收到中断信号，正在关闭服务器...")
                self.graceful_shutdown()
                break
            except Exception as e:
                # 改进异常处理，避免因为单次错误就退出命令循环
                if self.running and self.command_running:
                    print(f"命令输入错误: {e}")
                    print("命令输入线程将继续运行，请重新输入命令...")
                    # 短暂延迟后继续，避免快速循环
                    time.sleep(0.1)
                    continue
                else:
                    break
    
    def _handle_server_command(self, command):
        """处理服务器端命令"""
        parts = command.strip().split()
        if not parts:
            return
            
        cmd = parts[0].lower()
        
        if cmd == 'help':
            self._show_help()
        elif cmd == 'version':
            self._show_version()
        elif cmd == 'users':
            self._show_users()
        elif cmd == 'announce':
            if len(parts) > 1:
                announcement = ' '.join(parts[1:])
                self.broadcast_system_message(f"📢 公告: {announcement}")
                print(f"✅ 公告已发送: {announcement}")
            else:
                print("❌ 用法: announce <消息内容>")
        elif cmd == 'advertise':
            if len(parts) >= 2 and parts[1] == '--stop':
                self._stop_advertisement()
            elif len(parts) >= 3:
                try:
                    interval = int(parts[1])
                    ad_content = ' '.join(parts[2:])
                    self._start_advertisement(interval, ad_content)
                except ValueError:
                    print("❌ 错误: 时间间隔必须是数字")
            else:
                print("❌ 用法: advertise <时间间隔(秒)> <广告内容> 或 advertise --stop")
        elif cmd == 'ban':
            if len(parts) >= 2:
                ip_to_ban = parts[1]
                self._ban_ip(ip_to_ban)
            else:
                print("❌ 用法: ban <IP地址>")
        elif cmd == 'unban':
            if len(parts) >= 2:
                ip_to_unban = parts[1]
                self._unban_ip(ip_to_unban)
            else:
                print("❌ 用法: unban <IP地址>")
        elif cmd == 'wmassage':
            if len(parts) >= 3:
                target_ip = parts[1]
                message_content = ' '.join(parts[2:])
                self._send_popup_message_to_ip(target_ip, message_content)
            else:
                print("❌ 用法: wmassage <IP地址> <消息内容>")
        elif cmd == 'wannounce':
            if len(parts) > 1:
                announcement_content = ' '.join(parts[1:])
                self._send_popup_announcement(announcement_content)
            else:
                print("❌ 用法: wannounce <公告内容>")
        elif cmd == 'shutdown':
            self._handle_server_shutdown()
        else:
            print(f"❌ 未知命令: {cmd}。输入 'help' 查看可用命令")
    
    def _show_help(self):
        """显示帮助信息"""
        print("\n📋 可用的服务器管理命令:")
        print("  help                    - 显示此帮助信息")
        print("  version                 - 显示服务器版本")
        print("  users                   - 显示当前在线用户")
        print("  announce <消息>         - 发送系统公告")
        print("  advertise <间隔（秒）> <内容> - 循环发送广告")
        print("  advertise --stop        - 停止当前广告")
        print("  wmassage <IP> <内容>    - 向指定IP发送弹窗消息")
        print("  wannounce <内容>        - 发送弹窗公告给所有用户")
        print("  ban <IP地址>            - 禁止指定IP地址访问")
        print("  unban <IP地址>          - 解除指定IP地址的封禁")
        print("  shutdown                - 关闭服务器")
        print()
    
    def _show_version(self):
        """显示版本信息"""
        print(f"\n🔧 服务器版本: {self.SERVER_VERSION}")
        print(f"支持的客户端版本: {', '.join(self.SUPPORTED_CLIENT_VERSIONS)}\n")
    
    def _show_users(self):
        """显示当前在线用户列表"""
        with self.clients_lock:
            if not self.clients:
                print("\n👥 当前没有在线用户\n")
            else:
                print(f"\n👥 当前在线用户 ({len(self.clients)} 人):")
                for i, username in enumerate(self.clients.keys(), 1):
                    ip_address = self.user_ips.get(username, '未知')
                    print(f"  {i}. {username} ({ip_address})")
                print()
    
    def _start_advertisement(self, interval, content):
        """开始广告循环"""
        # 停止之前的广告
        if self.advertise_thread and self.advertise_thread.is_alive():
            self.advertise_stop_event.set()
            self.advertise_thread.join(timeout=1)
        
        # 重置停止事件
        self.advertise_stop_event.clear()
        
        # 启动新的广告线程
        self.advertise_thread = threading.Thread(
            target=self._advertise_worker, 
            args=(interval, content),
            daemon=True
        )
        self.advertise_thread.start()
        
        print(f"✅ 广告已启动，每 {interval} 秒发送一次: {content}")
    
    def _stop_advertisement(self):
        """停止广告循环"""
        if self.advertise_thread and self.advertise_thread.is_alive():
            self.advertise_stop_event.set()
            self.advertise_thread.join(timeout=1)
            print("✅ 广告已停止")
        else:
            print("❌ 当前没有运行中的广告")
    
    def _ban_ip(self, ip_address):
        """禁止指定IP地址"""
        # 验证IP地址格式
        import ipaddress
        try:
            ipaddress.ip_address(ip_address)
        except ValueError:
            print(f"❌ 无效的IP地址格式: {ip_address}")
            return
        
        # 检查是否为本地回环地址
        if ip_address in ['127.0.0.1', '::1', 'localhost']:
            print(f"⚠️  警告: 正在禁止本地回环地址 {ip_address}")
            print("   这可能会影响本地连接，但服务器命令功能不受影响")
        
        # 添加到黑名单
        self.banned_ips.add(ip_address)
        self._save_banned_ips()  # 保存到文件
        print(f"✅ IP地址 {ip_address} 已被禁止")
        
        # 断开该IP的所有现有连接
        with self.clients_lock:
            users_to_disconnect = []
            for username, client_info in self.clients.items():
                if username in self.user_ips and self.user_ips[username] == ip_address:
                    users_to_disconnect.append(username)
            
            for username in users_to_disconnect:
                try:
                    client_socket = self.clients[username]
                    
                    # 发送被禁止消息
                    ban_message = {
                        "type": "banned",
                        "content": "您的IP地址已被管理员禁止访问",
                        "timestamp": int(time.time())
                    }
                    
                    try:
                        self.send_message_to_client(client_socket, ban_message)
                        # 给客户端一点时间接收消息
                        time.sleep(0.1)
                    except Exception as send_error:
                        print(f"⚠️  向用户 {username} 发送封禁消息失败: {send_error}")
                    
                    # 安全关闭连接
                    try:
                        client_socket.shutdown(socket.SHUT_RDWR)
                    except Exception:
                        pass  # 忽略shutdown错误
                    
                    try:
                        client_socket.close()
                    except Exception:
                        pass  # 忽略close错误
                    
                    # 从客户端列表中移除
                    if username in self.clients:
                        del self.clients[username]
                    if username in self.user_ips:
                        del self.user_ips[username]
                    
                    print(f"✅ 已断开用户 {username} 的连接 (IP: {ip_address})")
                except Exception as e:
                    print(f"❌ 断开用户 {username} 连接时出错: {e}")
                    # 确保即使出错也要清理用户信息
                    try:
                        if username in self.clients:
                            del self.clients[username]
                        if username in self.user_ips:
                            del self.user_ips[username]
                    except Exception:
                        pass
            
            if users_to_disconnect:
                # 广播用户离开消息
                for username in users_to_disconnect:
                    leave_message = f"👋 {username} 已离开聊天室 (被管理员禁止)"
                    self.broadcast_system_message(leave_message)
                
                # 发送更新的用户列表
                self.send_user_list()
                
                # 在Linux环境下，刷新stdin缓冲区以确保命令输入正常
                if os.name == 'posix':
                    try:
                        import sys
                        sys.stdin.flush()
                    except Exception:
                        pass  # 忽略刷新错误
    
    def _unban_ip(self, ip_address):
        """解除指定IP地址的封禁"""
        # 验证IP地址格式
        import ipaddress
        try:
            ipaddress.ip_address(ip_address)
        except ValueError:
            print(f"❌ 无效的IP地址格式: {ip_address}")
            return
        
        # 检查IP是否在黑名单中
        if ip_address not in self.banned_ips:
            print(f"❌ IP地址 {ip_address} 不在黑名单中")
            return
        
        # 从黑名单中移除
        self.banned_ips.remove(ip_address)
        self._save_banned_ips()  # 保存到文件
        print(f"✅ IP地址 {ip_address} 已解除封禁")
    
    def _advertise_worker(self, interval, content):
        """广告工作线程"""
        while not self.advertise_stop_event.is_set():
            if self.advertise_stop_event.wait(interval):
                break  # 收到停止信号
            self.broadcast_system_message(f"📺 广告: {content}")
    
    def _handle_server_shutdown(self):
        """处理服务器关闭命令"""
        print("\n⚠️ 正在关闭服务器...")
        self.broadcast_system_message("⚠️ 服务器即将关闭 (管理员执行)")
        
        # 设置关闭标志
        self.shutdown_requested = True
        self.command_running = False
        
        # 在新线程中执行关闭操作
        shutdown_thread = threading.Thread(target=self._shutdown_server, daemon=True)
        shutdown_thread.start()
    
    def _shutdown_server(self):
        """关闭服务器"""
        time.sleep(2)  # 给客户端一些时间接收关闭消息
        
        # 停止广告线程
        if self.advertise_thread and self.advertise_thread.is_alive():
            self.advertise_stop_event.set()
            self.advertise_thread.join(timeout=1)
        
        self.graceful_shutdown()
        print("\n✅ 服务器已关闭")
        sys.exit(0)

    def send_user_list(self):
        with self.clients_lock:
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
            'timestamp': int(time.time() * 1000)  # 转换为毫秒时间戳整数
        }
        
        msg_json = json.dumps(message)
        msg_bytes = msg_json.encode('utf-8')
        header = struct.pack('!I', len(msg_bytes))
        
        with self.clients_lock:
            for username, client in self.clients.items():
                try:
                    client.sendall(header + msg_bytes)
                except:
                    pass

def run_as_daemon():
    """以守护进程模式运行服务器"""
    try:
        # 创建子进程
        pid = os.fork()
        if pid > 0:
            # 父进程退出
            print(f"服务器已在后台启动，进程ID: {pid}")
            print("使用 'kill {pid}' 或发送 SIGTERM 信号来停止服务器")
            sys.exit(0)
    except OSError as e:
        print(f"无法创建守护进程: {e}")
        sys.exit(1)
    
    # 子进程继续执行
    # 脱离父进程会话
    os.setsid()
    
    # 再次fork以确保不是会话领导者
    try:
        pid = os.fork()
        if pid > 0:
            sys.exit(0)
    except OSError as e:
        print(f"第二次fork失败: {e}")
        sys.exit(1)
    
    # 改变工作目录到根目录
    os.chdir('/')
    
    # 重定向标准输入输出
    sys.stdin.close()
    sys.stdout.close()
    sys.stderr.close()

if __name__ == "__main__":
    # 创建命令行参数解析器
    parser = argparse.ArgumentParser(description='intPlatinum 聊天服务器')
    parser.add_argument('--host', type=str, default='0.0.0.0', help='服务器绑定的主机地址 (默认: 0.0.0.0)')
    parser.add_argument('--port', type=int, default=7995, help='服务器绑定的端口号 (默认: 7995)')
    parser.add_argument('--daemon', '-d', action='store_true', help='以守护进程模式运行服务器（仅限Linux/Unix）')
    parser.add_argument('--background', '-b', action='store_true', help='在后台运行服务器（跨平台）')
    
    # 解析命令行参数
    args = parser.parse_args()
    
    # 检查是否以守护进程模式运行
    if args.daemon:
        if os.name != 'posix':
            print("守护进程模式仅在Linux/Unix系统上可用")
            print("在Windows上请使用 --background 参数")
            sys.exit(1)
        run_as_daemon()
    
    # 启动服务器，使用解析的主机和端口
    server = ChatServer(host=args.host, port=args.port)
    
    if args.background:
        print(f"服务器正在后台运行，监听 {args.host}:{args.port}")
        print("按 Ctrl+C 停止服务器")
        print("你现在可以继续使用终端执行其他命令")
        print("-" * 50)
    
    try:
        server.start()
    except KeyboardInterrupt:
        print("\n收到中断信号，正在关闭服务器...")
        server.graceful_shutdown()
    except SystemExit:
        # 正常退出，不需要额外处理
        pass
    except Exception as e:
        print(f"服务器运行时发生错误: {e}")
        try:
            server.graceful_shutdown()
        except:
            pass
        if os.name == 'posix':
            os._exit(1)
        else:
            sys.exit(1)