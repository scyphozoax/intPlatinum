#!/usr/bin/env python
# -*- coding: utf-8 -*-

import sys
import os
import json
import socket
import threading
import time
import base64
import struct
import random
import string
from datetime import datetime
from PyQt5.QtWidgets import (QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout,
                           QTextEdit, QTextBrowser, QLineEdit, QPushButton, QLabel, QListWidget,
                           QSplitter, QFileDialog, QMessageBox, QInputDialog, QMenu, QDialog, QSpinBox)
from PyQt5.QtGui import QColor, QTextCursor, QPixmap, QIcon, QFont, QTextDocument
from PyQt5.QtCore import Qt, QSize, pyqtSignal, QThread, QBuffer, QIODevice, QUrl

# 导入配置管理器
from config_manager import ConfigManager

# 确保存储目录存在
for dir_path in ['chat_files/text', 'chat_files/images']:
    os.makedirs(os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), dir_path), exist_ok=True)

class ChatClient(QThread):
    message_received = pyqtSignal(dict)
    connection_error = pyqtSignal(str)
    # 版本相关信号
    version_mismatch = pyqtSignal(str)
    
    # 定义客户端版本
    CLIENT_VERSION = "v1.0.0b"
    
    def __init__(self, host, port, username):
        super().__init__()
        # 解析可能包含URL格式的主机地址
        self.host, self.port = self._parse_host_address(host, port)
        self.username = username
        self.client_socket = None
        self.connected = False
        
    def _parse_host_address(self, host, port):
        """解析主机地址，支持普通IP/域名或URL格式
        例如：http://example.com:9999 或 ws://example.com
        """
        # 去除首尾空白字符
        host = host.strip()
        
        # 如果主机地址包含协议部分，尝试解析
        if '://' in host:
            try:
                # 提取协议后的部分
                _, address_part = host.split('://', 1)
                # 移除路径部分
                if '/' in address_part:
                    address_part = address_part.split('/', 1)[0]
                # 提取主机和端口
                if ':' in address_part:
                    host = address_part.split(':', 1)[0]
                    # 尝试解析端口
                    try:
                        parsed_port = int(address_part.split(':', 1)[1])
                        # 确保端口在有效范围内
                        if 1 <= parsed_port <= 65535:
                            port = parsed_port  # 如果URL中包含端口，则使用URL中的端口
                    except ValueError:
                        # 端口解析失败，保留原来的端口
                        pass
                else:
                    host = address_part
            except Exception:
                # 解析过程中出现任何错误，保留原始主机地址
                pass
        return host, port
        
    def receive_all(self, sock, n):
        # 接收指定长度的数据
        data = b''
        while len(data) < n:
            packet = sock.recv(n - len(data))
            if not packet:
                return None
            data += packet
        return data
        
    def connect_to_server(self):
        try:
            self.client_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.client_socket.connect((self.host, self.port))
            
            # 发送版本信息，使用base64加密版本号
            encrypted_version = base64.b64encode(self.CLIENT_VERSION.encode('utf-8')).decode('utf-8')
            version_data = json.dumps({'version': encrypted_version})
            self.client_socket.sendall(version_data.encode('utf-8'))
            
            # 等待服务器版本验证响应 - 先接收4字节的消息头
            header_data = self.client_socket.recv(4)
            if not header_data:
                self.connection_error.emit("服务器未响应版本验证")
                self.client_socket.close()
                return False
                
            # 解析消息长度
            try:
                msg_len = struct.unpack('!I', header_data)[0]
            except:
                self.connection_error.emit("版本验证消息格式错误")
                self.client_socket.close()
                return False
                
            # 接收完整的消息内容
            version_response_data = self.receive_all(self.client_socket, msg_len)
            if not version_response_data:
                self.connection_error.emit("服务器未响应版本验证")
                self.client_socket.close()
                return False
                
            # 解码并解析JSON
            try:
                version_response = json.loads(version_response_data.decode('utf-8'))
            except json.JSONDecodeError:
                self.connection_error.emit("版本验证响应不是有效的JSON格式")
                self.client_socket.close()
                return False
            
            # 检查版本验证响应
            if version_response.get('type') == 'version_mismatch':
                # 版本不匹配
                required_version = version_response.get('required_version', '未知版本')
                self.version_mismatch.emit(required_version)
                self.client_socket.close()
                return False
            elif version_response.get('type') != 'version_accepted':
                # 版本验证失败，不是预期的响应
                self.connection_error.emit(f"版本验证失败: {version_response}")
                self.client_socket.close()
                return False
            
            # 版本验证通过后，发送昵称
            username_data = json.dumps({'username': self.username})
            self.client_socket.sendall(username_data.encode('utf-8'))
            
            # 等待服务器响应 - 先接收4字节的消息头
            header_data = self.client_socket.recv(4)
            if not header_data:
                self.connection_error.emit("服务器未响应")
                self.client_socket.close()
                return False
                
            # 解析消息长度
            msg_len = struct.unpack('!I', header_data)[0]
            
            # 接收完整的消息内容
            msg_data = self.receive_all(self.client_socket, msg_len)
            if not msg_data:
                self.connection_error.emit("服务器未响应")
                self.client_socket.close()
                return False
                
            # 解码并解析JSON
            message = json.loads(msg_data.decode('utf-8'))
            
            # 检查响应类型
            if message.get('type') == 'error':
                # 昵称重复错误
                self.connection_error.emit(message.get('content', '连接错误'))
                self.client_socket.close()
                return False
            elif message.get('type') == 'connected':
                # 连接成功
                self.connected = True
                return True
            else:
                # 未知响应
                self.connection_error.emit("未知的服务器响应")
                self.client_socket.close()
                return False
        except Exception as e:
            self.connection_error.emit(f"连接服务器失败: {e}")
            return False
    
    def run(self):
        if not self.connect_to_server():
            return
            
        try:
            while self.connected:
                header_data = self.client_socket.recv(4)
                if not header_data:
                    break
                    
                msg_len = struct.unpack('!I', header_data)[0]
                data = self.recv_all(msg_len)
                
                if not data:
                    break
                    
                message = json.loads(data.decode('utf-8'))
                self.message_received.emit(message)
                
        except Exception as e:
            # 忽略已知的连接错误类型
            error_str = str(e)
            # 这些是正常关闭时可能出现的错误，不需要显示给用户
            if not self.connected or "远程主机强迫关闭了一个现有的连接" in error_str or "[WinError 10053]" in error_str:
                pass  # 这是预期的断开连接，不显示错误
            else:
                self.connection_error.emit(f"接收消息错误: {e}")
        finally:
            if self.connected:  # 只有在未正常断开的情况下才设置为False
                self.connected = False
            if self.client_socket:
                try:
                    self.client_socket.close()
                except:
                    pass
    
    def recv_all(self, n):
        data = b''
        while len(data) < n:
            packet = self.client_socket.recv(n - len(data))
            if not packet:
                return None
            data += packet
        return data
    
    def send_message(self, message_type, content):
        if not self.connected:
            self.connection_error.emit("未连接到服务器")
            return False
            
        try:
            message = {
                'type': message_type,
                'content': content
            }
            
            msg_json = json.dumps(message)
            msg_bytes = msg_json.encode('utf-8')
            header = struct.pack('!I', len(msg_bytes))
            
            self.client_socket.sendall(header + msg_bytes)
            return True
        except Exception as e:
            self.connection_error.emit(f"发送消息错误: {e}")
            return False
    
    def send_file(self, file_path, file_type):
        if not self.connected:
            self.connection_error.emit("未连接到服务器")
            return False
            
        try:
            with open(file_path, 'rb') as f:
                file_data = f.read()
                
            # 获取原始文件名和扩展名
            original_file_name = os.path.basename(file_path)
            file_name_only, file_ext = os.path.splitext(original_file_name)
            
            # 生成混淆文件名（时间戳+随机字符串+原始扩展名）
            timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
            random_str = ''.join(random.choices(string.ascii_letters + string.digits, k=8))
            obfuscated_file_name = f"{timestamp}_{random_str}{file_ext}"
            
            file_data_b64 = base64.b64encode(file_data).decode('utf-8')
            
            message = {
                'type': 'file',
                'file_type': file_type,
                'file_name': obfuscated_file_name,
                'original_file_name': original_file_name,  # 保留原始文件名用于显示
                'file_data': file_data_b64
            }
            
            msg_json = json.dumps(message)
            msg_bytes = msg_json.encode('utf-8')
            header = struct.pack('!I', len(msg_bytes))
            
            self.client_socket.sendall(header + msg_bytes)
            return True
        except Exception as e:
            self.connection_error.emit(f"发送文件错误: {e}")
            return False
    
    def disconnect(self):
        # 发送断开连接通知给服务器
        try:
            if self.connected and self.client_socket:
                # 创建一个断开连接的消息
                disconnect_message = {
                    'type': 'disconnect',
                    'username': self.username
                }
                msg_json = json.dumps(disconnect_message)
                msg_bytes = msg_json.encode('utf-8')
                header = struct.pack('!I', len(msg_bytes))
                # 尝试发送断开连接消息，但不关心是否成功
                try:
                    self.client_socket.sendall(header + msg_bytes)
                    # 给服务器一点时间处理消息
                    time.sleep(0.1)
                except:
                    pass
                # 关闭套接字
                self.client_socket.close()
        finally:
            self.connected = False

class ServerInfoDialog(QDialog):
    """服务器信息输入对话框"""
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle("服务器信息")
        self.setMinimumWidth(350)  # 增加窗口最小宽度
        
        # 直接设置对话框背景颜色为暗黑模式
        self.setStyleSheet("background-color: #2c2c2c;")
        
        # 创建布局
        layout = QVBoxLayout(self)
        layout.setSpacing(15)  # 增加布局间距
        layout.setContentsMargins(20, 20, 20, 20)  # 增加边距
        
        # 服务器地址（合并地址和端口）
        host_layout = QHBoxLayout()
        host_layout.setSpacing(10)
        host_label = QLabel("服务器地址:")
        host_label.setStyleSheet("color: #ffffff; font-size: 14px;")
        self.host_input = QLineEdit("localhost:7995")
        self.host_input.setStyleSheet("background-color: #3c3c3c; color: #ffffff; border: 1px solid #555555; padding: 8px; font-size: 14px;")
        self.host_input.setMinimumHeight(32)  # 增加输入框高度
        self.host_input.setPlaceholderText("格式：example.com:port 或 example.com（默认端口7995）")
        host_layout.addWidget(host_label)
        host_layout.addWidget(self.host_input)
        
        # 提示标签
        hint_label = QLabel("支持格式：IP地址:端口 或 域名:端口，如不指定端口则默认为7995")
        hint_label.setStyleSheet("color: #999999; font-size: 12px;")
        hint_label.setWordWrap(True)
        
        # 按钮
        button_layout = QHBoxLayout()
        button_layout.setSpacing(15)
        button_layout.setAlignment(Qt.AlignCenter)
        self.ok_button = QPushButton("确定")
        self.ok_button.setStyleSheet("background-color: #2e7d32; color: white; border: none; padding: 10px 24px; border-radius: 6px; font-size: 14px; font-weight: bold;")
        self.ok_button.setMinimumHeight(36)  # 增加按钮高度
        self.cancel_button = QPushButton("取消")
        self.cancel_button.setStyleSheet("background-color: #555555; color: white; border: none; padding: 10px 24px; border-radius: 6px; font-size: 14px;")
        self.cancel_button.setMinimumHeight(36)  # 增加按钮高度
        button_layout.addWidget(self.ok_button)
        button_layout.addWidget(self.cancel_button)
        
        # 添加到主布局
        layout.addLayout(host_layout)
        layout.addWidget(hint_label)
        layout.addLayout(button_layout)
        
        # 连接信号
        self.ok_button.clicked.connect(self.accept)
        self.cancel_button.clicked.connect(self.reject)
        
    def get_server_info(self):
        """获取服务器信息，解析合并格式的地址"""
        # 获取输入的地址
        input_text = self.host_input.text().strip()
        
        # 默认端口
        default_port = 7995
        
        # 如果输入为空，返回默认值
        if not input_text:
            return "localhost", default_port
        
        # 检查是否包含端口号
        if ':' in input_text:
            try:
                # 尝试分割地址和端口
                host, port_str = input_text.rsplit(':', 1)  # 从右边分割，避免URL中的端口问题
                # 尝试解析端口号
                port = int(port_str)
                # 验证端口号范围
                if 1 <= port <= 65535:
                    return host, port
                else:
                    # 端口号无效，使用默认端口
                    return host, default_port
            except ValueError:
                # 端口解析失败，使用默认端口
                return input_text, default_port
        else:
            # 不包含端口号，使用默认端口
            return input_text, default_port

class NicknameDialog(QDialog):
    """自定义昵称输入对话框"""
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle("登录")
        self.setMinimumWidth(350)  # 增加窗口最小宽度
        
        # 创建布局
        layout = QVBoxLayout(self)
        layout.setSpacing(15)  # 增加布局间距
        layout.setContentsMargins(20, 20, 20, 20)  # 增加边距
        
        # 提示标签
        prompt_label = QLabel("请输入您的昵称:")
        prompt_label.setStyleSheet("color: #ffffff; font-size: 14px;")
        
        # 昵称输入框
        self.nickname_input = QLineEdit()
        self.nickname_input.setStyleSheet("background-color: #3c3c3c; color: #ffffff; border: 1px solid #555555; padding: 8px; font-size: 14px;")
        self.nickname_input.setMinimumHeight(32)  # 增加输入框高度
        self.nickname_input.setPlaceholderText("请输入昵称...")
        
        # 按钮
        button_layout = QHBoxLayout()
        button_layout.setSpacing(15)
        button_layout.setAlignment(Qt.AlignCenter)
        self.ok_button = QPushButton("确定")
        self.ok_button.setStyleSheet("background-color: #2e7d32; color: white; border: none; padding: 10px 24px; border-radius: 6px; font-size: 14px; font-weight: bold;")
        self.ok_button.setMinimumHeight(36)  # 增加按钮高度
        self.cancel_button = QPushButton("取消")
        self.cancel_button.setStyleSheet("background-color: #555555; color: white; border: none; padding: 10px 24px; border-radius: 6px; font-size: 14px;")
        self.cancel_button.setMinimumHeight(36)  # 增加按钮高度
        button_layout.addWidget(self.ok_button)
        button_layout.addWidget(self.cancel_button)
        
        # 添加到主布局
        layout.addWidget(prompt_label)
        layout.addWidget(self.nickname_input)
        layout.addLayout(button_layout)
        
        # 连接信号
        self.ok_button.clicked.connect(self.accept)
        self.cancel_button.clicked.connect(self.reject)
        self.nickname_input.returnPressed.connect(self.accept)  # 回车键确认
        
        # 设置暗黑模式样式
        self.setStyleSheet(".QDialog {background-color: #2c2c2c;}")
        
    def get_nickname(self):
        """获取输入的昵称"""
        return self.nickname_input.text()

class ChatWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        
        # 初始化配置管理器
        self.config_manager = ConfigManager()
        
        # 加载保存的配置
        saved_host, saved_port = self.config_manager.get_server_info()
        saved_username = self.config_manager.get_username()
        
        # 获取服务器信息，使用保存的配置作为默认值
        server_dialog = ServerInfoDialog(self)
        # 格式化保存的配置为合并格式
        formatted_host = f"{saved_host}:{saved_port}" if saved_host else "localhost:9999"
        server_dialog.host_input.setText(formatted_host)
        
        if server_dialog.exec_() != QDialog.Accepted:
            sys.exit(0)
            
        self.server_host, self.server_port = server_dialog.get_server_info()
        
        # 初始化UI
        self.init_ui()
        
        # 存储用户IP地址的字典
        self.user_ips = {}
        
        # 记录上次发送消息的时间，初始为0
        self.last_message_time = 0
        
        # 然后循环获取昵称直到成功
        while True:
            # 使用自定义的昵称输入对话框，设置保存的用户名为默认值
            nickname_dialog = NicknameDialog(self)
            if saved_username:
                nickname_dialog.nickname_input.setText(saved_username)
            
            if nickname_dialog.exec_() == QDialog.Accepted:
                self.username = nickname_dialog.get_nickname()
                if not self.username:
                    # 如果用户输入为空，显示提示
                    msg_box = QMessageBox()
                    msg_box.setWindowTitle("提示")
                    msg_box.setText("昵称不能为空，请重新输入！")
                    msg_box.setIcon(QMessageBox.Warning)
                    msg_box.setStyleSheet("""
                        QMessageBox { background-color: #2c2c2c; color: #ffffff; min-width: 350px; }
                        QLabel { color: #ffffff; font-size: 14px; padding: 10px; }
                        QPushButton { background-color: #555555; color: #ffffff; border: none; padding: 10px 20px; border-radius: 6px; font-size: 14px; min-height: 32px; }
                        QPushButton:hover { background-color: #666666; }
                        QPushButton:pressed { background-color: #777777; }
                    """)
                    msg_box.exec_()
                    continue
                
                # 尝试连接服务器
                self.client = ChatClient(self.server_host, self.server_port, self.username)
                self.client.message_received.connect(self.handle_message)
                self.client.connection_error.connect(self.show_error)
                self.client.version_mismatch.connect(self.show_version_error)
                
                # 直接调用connect_to_server方法尝试连接
                if self.client.connect_to_server():
                    # 连接成功，开始接收消息线程
                    self.client.start()
                    # 保存当前配置
                    self.config_manager.save_config(self.server_host, self.server_port, self.username)
                    break
                # 连接失败会通过signal显示错误，继续循环
            else:
                sys.exit(0)
            
        # 设置窗口标题
        self.setWindowTitle(f"intPlatinum - {self.username}")
        
    def init_ui(self):
        # 主窗口设置
        self.setGeometry(100, 100, 800, 600)
        
        # 初始化暗黑模式状态 - 默认使用暗黑模式
        self.is_dark_mode = True
        
        # 主布局
        main_widget = QWidget()
        main_layout = QHBoxLayout(main_widget)
        
        # 创建分割器
        splitter = QSplitter(Qt.Horizontal)
        
        # 左侧面板（用户列表）
        left_panel = QWidget()
        left_layout = QVBoxLayout(left_panel)
        
        # 左侧面板顶部区域（标题）
        top_bar_layout = QHBoxLayout()
        
        self.user_list_label = QLabel("在线用户")
        self.user_list_label.setAlignment(Qt.AlignCenter)
        self.user_list_label.setStyleSheet("font-weight: bold; font-size: 14px; color: #ffffff;")
        
        top_bar_layout.addWidget(self.user_list_label, 1)
        
        # 用户列表
        self.user_list = QListWidget()
        self.user_list.setMinimumWidth(180)
        self.user_list.setMaximumWidth(220)
        
        # 为用户列表添加点击事件
        self.user_list.itemClicked.connect(self.show_user_info)
        
        left_layout.addLayout(top_bar_layout)
        left_layout.addWidget(self.user_list)
        
        # 右侧面板（聊天区域）
        right_panel = QWidget()
        right_layout = QVBoxLayout(right_panel)
        
        # 聊天显示区域
        self.chat_display = QTextBrowser()
        self.chat_display.setReadOnly(True)
        self.chat_display.setOpenExternalLinks(True)  # 允许打开外部链接
        
        # 输入区域
        input_panel = QWidget()
        input_layout = QHBoxLayout(input_panel)
        
        self.message_input = QLineEdit()
        self.message_input.setPlaceholderText("输入消息...")
        self.message_input.returnPressed.connect(self.send_text_message)
        
        self.send_button = QPushButton("发送")
        self.send_button.clicked.connect(self.send_text_message)
        
        self.file_button = QPushButton("文件")
        self.file_button.clicked.connect(self.show_file_menu)
        
        input_layout.addWidget(self.message_input, 4)
        input_layout.addWidget(self.send_button, 1)
        input_layout.addWidget(self.file_button, 1)
        input_layout.setContentsMargins(5, 5, 5, 5)
        
        right_layout.addWidget(self.chat_display)
        right_layout.addWidget(input_panel)
        
        # 添加到分割器
        splitter.addWidget(left_panel)
        splitter.addWidget(right_panel)
        splitter.setSizes([200, 600])
        
        main_layout.addWidget(splitter)
        self.setCentralWidget(main_widget)
        
        # 应用暗黑模式样式
        self.apply_dark_mode()
        
    def apply_dark_mode(self):
        """应用暗黑模式样式"""
        # 全局应用样式表，确保对话框也能应用暗黑模式
        app_style = """
            QMainWindow, QWidget { background-color: #2c2c2c; }
            QDialog { background-color: #2c2c2c; color: #ffffff; }
            QMessageBox { background-color: #2c2c2c; color: #ffffff; }
            QPushButton { background-color: #5c5c5c; color: #ffffff; border: 1px solid #777777; }
            QPushButton:hover { background-color: #6c6c6c; }
        """
        
        # 设置应用全局样式
        self.setStyleSheet(app_style)
        
        # 用户列表样式
        self.user_list.setStyleSheet("""
            QListWidget { background-color: #3c3c3c; color: #ffffff; border: 1px solid #555555; border-radius: 5px; }
            QListWidget::item { padding: 10px; border-bottom: 1px solid #444444; }
            QListWidget::item:hover { background-color: #4c4c4c; }
            QListWidget::item:selected { background-color: #5c5c5c; color: #ffffff; }
        """)
        
        # 聊天显示区域样式
        self.chat_display.setStyleSheet("""
            background-color: #1e1e1e;
            color: #ffffff;
            border: 1px solid #555555;
            border-radius: 5px;
            padding: 10px;
            font-size: 14px;
        """)
        
        # 输入框样式
        self.message_input.setStyleSheet("""
            background-color: #3c3c3c;
            color: #ffffff;
            padding: 8px;
            border: 1px solid #555555;
            border-radius: 18px;
            font-size: 14px;
        """)
        
        # 发送按钮样式
        self.send_button.setStyleSheet("""
            background-color: #2e7d32;
            color: white;
            border: none;
            border-radius: 18px;
            padding: 8px 16px;
            font-size: 14px;
        """)
        
        # 文件按钮样式
        self.file_button.setStyleSheet("""
            background-color: #1565c0;
            color: white;
            border: none;
            border-radius: 18px;
            padding: 8px 16px;
            font-size: 14px;
            margin-left: 5px;
        """)
        
    def apply_dark_mode(self):
        """应用暗黑模式样式"""
        # 全局应用样式表，确保对话框也能应用暗黑模式
        app_style = """
            QMainWindow, QWidget { background-color: #2c2c2c; }
            QDialog { background-color: #3c3c3c; color: #ffffff; }
            QMessageBox { background-color: #3c3c3c; color: #ffffff; }
            QPushButton { background-color: #5c5c5c; color: #ffffff; border: 1px solid #777777; }
            QPushButton:hover { background-color: #6c6c6c; }
        """
        
        # 设置应用全局样式
        self.setStyleSheet(app_style)
        
        # 用户列表样式
        self.user_list.setStyleSheet("""
            QListWidget { background-color: #3c3c3c; color: #ffffff; border: 1px solid #555555; border-radius: 5px; }
            QListWidget::item { padding: 10px; border-bottom: 1px solid #444444; }
            QListWidget::item:hover { background-color: #4c4c4c; }
            QListWidget::item:selected { background-color: #5c5c5c; color: #ffffff; }
        """)
        
        # 聊天显示区域样式
        self.chat_display.setStyleSheet("""
            background-color: #1e1e1e;
            color: #ffffff;
            border: 1px solid #555555;
            border-radius: 5px;
            padding: 10px;
            font-size: 14px;
        """)
        
        # 输入框样式
        self.message_input.setStyleSheet("""
            background-color: #3c3c3c;
            color: #ffffff;
            padding: 8px;
            border: 1px solid #555555;
            border-radius: 18px;
            font-size: 14px;
        """)
        
        # 发送按钮样式
        self.send_button.setStyleSheet("""
            background-color: #2e7d32;
            color: white;
            border: none;
            border-radius: 18px;
            padding: 8px 16px;
            font-size: 14px;
        """)
        
        # 文件按钮样式
        self.file_button.setStyleSheet("""
            background-color: #1565c0;
            color: white;
            border: none;
            border-radius: 18px;
            padding: 8px 16px;
            font-size: 14px;
            margin-left: 5px;
        """)
    
    def show_file_menu(self):
        """显示文件发送菜单"""
        menu = QMenu(self)
        menu.setStyleSheet("""
            QMenu { background-color: #3c3c3c; color: #ffffff; border: 1px solid #555555; border-radius: 5px; }
            QMenu::item { padding: 8px 20px; }
            QMenu::item:hover { background-color: #4c4c4c; }
        """ if self.is_dark_mode else """
            QMenu { background-color: #ffffff; color: #000000; border: 1px solid #ddd; border-radius: 5px; }
            QMenu::item { padding: 8px 20px; }
            QMenu::item:hover { background-color: #f0f0f0; }
        """)
        
        image_action = menu.addAction("发送图片")
        image_action.triggered.connect(lambda: self.send_file('images'))
        
        # 计算菜单显示位置，确保不会超出屏幕
        pos = self.file_button.mapToGlobal(self.file_button.rect().bottomLeft())
        # 调整位置使其更美观
        pos.setY(pos.y() + 5)
        menu.exec_(pos)
    
    def send_text_message(self):
        message = self.message_input.text().strip()
        if not message:
            return
        
        # 获取当前时间
        current_time = time.time()
        
        # 检查距离上次发送消息的时间是否小于3秒
        if current_time - self.last_message_time < 3:
            # 显示提示信息
            remaining_time = 3 - int(current_time - self.last_message_time)
            msg_box = QMessageBox()
            msg_box.setWindowTitle("发送提示")
            msg_box.setText(f"请等待{remaining_time}秒后再发送。")
            msg_box.setIcon(QMessageBox.Information)
            msg_box.setStyleSheet("""
                QMessageBox { background-color: #2c2c2c; color: #ffffff; min-width: 350px; }
                QLabel { color: #ffffff; font-size: 14px; padding: 10px; }
                QPushButton { background-color: #555555; color: #ffffff; border: none; padding: 10px 20px; border-radius: 6px; font-size: 14px; min-height: 32px; }
                QPushButton:hover { background-color: #666666; }
                QPushButton:pressed { background-color: #777777; }
            """)
            msg_box.exec_()
            return
        
        if self.client.send_message('text', message):
            self.message_input.clear()
            # 更新上次发送消息的时间
            self.last_message_time = time.time()
            
            # 保存到本地
            self.save_text_message(self.username, message)
    
    def send_file(self, file_type):
        """发送文件（图片）功能"""
        if file_type == 'images':
            file_path, _ = QFileDialog.getOpenFileName(self, "选择图片", "", "图片文件 (*.png *.jpg *.jpeg *.gif)")

        if not file_path:
            return
            
        # 获取原始文件名
        original_file_name = os.path.basename(file_path)
            
        try:
            if self.client.send_file(file_path, file_type):
                # 发送成功后立即在本地显示图片
                # 注意：由于我们在ChatClient的send_file方法中处理了文件名混淆，
                # 服务器会转发混淆后的文件名，但我们在本地显示时仍然使用原始文件名
                self.display_file_message(self.username, file_type, original_file_name, original_file_name)
            else:
                # 文件发送失败
                self.chat_display.append(f"[发送图片失败: {original_file_name}]")
        except Exception as e:
            print(f"发送文件时发生错误: {e}")
            self.chat_display.append(f"[发送图片时发生错误: {original_file_name}]")
                     
    def display_file_from_path(self, sender, file_path):
        """从指定路径直接显示文件"""
        try:
            # 创建QPixmap并插入到文本编辑器
            pixmap = QPixmap(file_path)
            if not pixmap.isNull():
                # 限制图片最大宽度为聊天窗口的80%
                max_width = int(self.chat_display.width() * 0.8)
                if pixmap.width() > max_width:
                    pixmap = pixmap.scaledToWidth(max_width, Qt.SmoothTransformation)
                
                # 使用绝对路径作为图片资源的URL
                abs_path = os.path.abspath(file_path)
                self.chat_display.document().addResource(
                    QTextDocument.ImageResource,
                    QUrl.fromLocalFile(abs_path),
                    pixmap
                )
                self.chat_display.textCursor().insertImage(QUrl.fromLocalFile(abs_path).toString())
                self.chat_display.append("")
            else:
                print(f"图片加载失败: {file_path}")
                self.chat_display.append("[图片无法显示]")
        except Exception as e:
            print(f"图片显示错误: {e}")
            self.chat_display.append("[图片无法显示]")

    def handle_message(self, message):
        msg_type = message.get('type')
        
        if msg_type == 'text':
            sender = message.get('sender')
            content = message.get('content')
            timestamp = message.get('timestamp')
            
            self.display_text_message(sender, content, timestamp)
            
            # 保存到本地（如果不是自己发送的）
            if sender != self.username:
                self.save_text_message(sender, content)
                
        elif msg_type == 'file':
            sender = message.get('sender')
            file_type = message.get('file_type')
            file_name = message.get('file_name')
            original_file_name = message.get('original_file_name', file_name)  # 如果没有原始文件名，使用混淆后的文件名
            file_data_b64 = message.get('file_data')
            timestamp = message.get('timestamp')
            
            # 对于发送者自己的消息，我们已经在发送时立即显示了，所以这里不需要再显示
            # 只需要处理接收的文件
            if sender != self.username:
                # 保存并显示接收的文件，传入原始文件名用于显示
                self.save_received_file(sender, file_type, file_name, original_file_name, file_data_b64)
                
        elif msg_type == 'system':
            content = message.get('content')
            timestamp = message.get('timestamp')
            
            self.display_system_message(content, timestamp)
            
        elif msg_type == 'user_list':
            users = message.get('users', [])
            self.update_user_list(users)
    
    def display_text_message(self, sender, content, timestamp=None):
        cursor = self.chat_display.textCursor()
        cursor.movePosition(QTextCursor.End)
        self.chat_display.setTextCursor(cursor)
        
        time_str = datetime.fromtimestamp(timestamp).strftime('%H:%M:%S') if timestamp else datetime.now().strftime('%H:%M:%S')
        
        # 设置发送者名称颜色
        sender_color = "#90EE90" if sender == self.username else "#ADD8E6"
        
        # 构建HTML消息内容，使用普通文本显示用户名
        html_message = f'<font color="{sender_color}"><b>{sender}</b> [{time_str}]:<br></font>'
        html_message += f'<font color="#FFFFFF">{content.replace("<", "&lt;").replace(">", "&gt;")}</font><br><br>'
        
        # 插入HTML消息
        self.chat_display.insertHtml(html_message)
        
        # 滚动到底部
        self.chat_display.verticalScrollBar().setValue(self.chat_display.verticalScrollBar().maximum())
    
    def display_file_message(self, sender, file_type, file_name, original_file_name=None, timestamp=None):
        cursor = self.chat_display.textCursor()
        cursor.movePosition(QTextCursor.End)
        self.chat_display.setTextCursor(cursor)
        
        time_str = datetime.fromtimestamp(timestamp).strftime('%H:%M:%S') if timestamp else datetime.now().strftime('%H:%M:%S')
        
        # 暗黑模式下设置发送者名称的颜色
        self.chat_display.setTextColor(QColor(144, 238, 144) if sender == self.username else QColor(173, 216, 230))
        
        self.chat_display.setFontWeight(QFont.Bold)
        self.chat_display.insertPlainText(f"{sender} [{time_str}]:\n")
        
        # 暗黑模式下设置文件消息的颜色
        self.chat_display.setTextColor(QColor(218, 112, 214))
        self.chat_display.setFontWeight(QFont.Normal)
        
        base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        file_type_str = "图片"
        
        # 使用原始文件名显示，如果没有则使用混淆后的文件名
        display_file_name = original_file_name if original_file_name else file_name
        
        if file_type == "images":
            # 显示图片
            file_path = os.path.join(base_dir, 'chat_files', file_type, file_name)
            print(f"尝试显示图片: {file_path}")
            
            # 先检查文件是否存在
            if os.path.exists(file_path):
                self.chat_display.insertPlainText(f"[发送了{file_type_str}: {display_file_name}]\n")
                
                try:
                    # 创建QPixmap并插入到文本编辑器
                    pixmap = QPixmap(file_path)
                    if not pixmap.isNull():
                        # 限制图片最大宽度为聊天窗口的80%
                        max_width = int(self.chat_display.width() * 0.8)
                        if pixmap.width() > max_width:
                            pixmap = pixmap.scaledToWidth(max_width, Qt.SmoothTransformation)
                        
                        # 使用绝对路径作为图片资源的URL
                        abs_path = os.path.abspath(file_path)
                        self.chat_display.document().addResource(
                            QTextDocument.ImageResource,
                            QUrl.fromLocalFile(abs_path),
                            pixmap
                        )
                        self.chat_display.textCursor().insertImage(QUrl.fromLocalFile(abs_path).toString())
                        self.chat_display.insertPlainText("\n\n")
                    else:
                        print(f"图片加载失败: {file_path}")
                        self.chat_display.insertPlainText("[图片无法显示]\n\n")
                except Exception as e:
                    print(f"图片显示错误: {e}")
                    self.chat_display.insertPlainText("[图片无法显示]\n\n")
            else:
                print(f"文件不存在: {file_path}")
                self.chat_display.insertPlainText(f"[发送了{file_type_str}: {file_name}]\n\n")

        
        # 滚动到底部
        self.chat_display.verticalScrollBar().setValue(self.chat_display.verticalScrollBar().maximum())
    
    def display_system_message(self, content, timestamp=None):
        """显示系统消息"""
        cursor = self.chat_display.textCursor()
        cursor.movePosition(QTextCursor.End)
        self.chat_display.setTextCursor(cursor)
        
        time_str = datetime.fromtimestamp(timestamp).strftime('%H:%M:%S') if timestamp else datetime.now().strftime('%H:%M:%S')
        
        # 暗黑模式下设置系统消息的颜色为橙色（更醒目）
        self.chat_display.setTextColor(QColor(255, 165, 0))
        self.chat_display.setFontWeight(QFont.Bold)
        self.chat_display.insertPlainText(f"系统消息 [{time_str}]:\n{content}\n\n")
        
        # 滚动到底部
        self.chat_display.verticalScrollBar().setValue(self.chat_display.verticalScrollBar().maximum())
    

    
    def show_user_info(self, item):
        """显示用户信息（从用户列表点击）"""
        username = item.text()
        ip_address = self.user_ips.get(username, '未知')
        
        # 显示用户信息对话框，传入IP地址
        dialog = UserInfoDialog(username, ip_address, self)
        dialog.exec_()
        

    
    def update_user_list(self, users):
        self.user_list.clear()
        # 清空并重建用户IP地址字典
        self.user_ips.clear()
        
        for user_info in users:
            if isinstance(user_info, dict):
                # 新格式：包含用户名和IP地址
                username = user_info.get('username')
                ip_address = user_info.get('ip', '未知')
                self.user_list.addItem(username)
                self.user_ips[username] = ip_address
            else:
                # 向后兼容：旧格式只包含用户名
                self.user_list.addItem(user_info)
                self.user_ips[user_info] = '未知'
    
    def save_text_message(self, sender, content):
        try:
            base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
            file_path = os.path.join(base_dir, 'chat_files', 'text', f"{datetime.now().strftime('%Y%m%d')}.txt")
            
            with open(file_path, 'a', encoding='utf-8') as f:
                time_str = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
                f.write(f"[{time_str}] {sender}: {content}\n")
        except Exception as e:
            print(f"保存文本消息错误: {e}")
    
    def save_file(self, sender, file_path, file_type):
        try:
            base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
            file_name = os.path.basename(file_path)
            dest_path = os.path.join(base_dir, 'chat_files', file_type, file_name)
            
            # 复制文件
            with open(file_path, 'rb') as src_file, open(dest_path, 'wb') as dest_file:
                dest_file.write(src_file.read())
                
            # 记录到文本日志
            log_path = os.path.join(base_dir, 'chat_files', 'text', f"{datetime.now().strftime('%Y%m%d')}.txt")
            with open(log_path, 'a', encoding='utf-8') as f:
                time_str = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
                file_type_str = "图片"
                f.write(f"[{time_str}] {sender} 发送了{file_type_str}: {file_name}\n")
        except Exception as e:
            print(f"保存文件错误: {e}")
    
    def save_received_file(self, sender, file_type, file_name, original_file_name, file_data_b64):
        try:
            base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
            dest_path = os.path.join(base_dir, 'chat_files', file_type, file_name)
            
            # 解码并保存文件
            file_data = base64.b64decode(file_data_b64)
            with open(dest_path, 'wb') as f:
                f.write(file_data)
                
            # 记录到文本日志
            log_path = os.path.join(base_dir, 'chat_files', 'text', f"{datetime.now().strftime('%Y%m%d')}.txt")
            with open(log_path, 'a', encoding='utf-8') as f:
                time_str = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
                file_type_str = "图片"
                f.write(f"[{time_str}] {sender} 发送了{file_type_str}: {original_file_name}\n")
                
            # 显示接收到的文件消息，传入原始文件名用于显示
            self.display_file_message(sender, file_type, file_name, original_file_name)
        except Exception as e:
            print(f"保存接收文件错误: {e}")
    
    def show_error(self, error_message):
        """显示错误消息，使用暗黑模式样式"""
        msg_box = QMessageBox()
        msg_box.setWindowTitle("错误")
        msg_box.setText(error_message)
        msg_box.setIcon(QMessageBox.Critical)
        msg_box.setStyleSheet("""
            QMessageBox { background-color: #2c2c2c; color: #ffffff; min-width: 350px; }
            QLabel { color: #ffffff; font-size: 14px; padding: 10px; }
            QPushButton { background-color: #555555; color: #ffffff; border: none; padding: 10px 20px; border-radius: 6px; font-size: 14px; min-height: 32px; }
            QPushButton:hover { background-color: #666666; }
            QPushButton:pressed { background-color: #777777; }
        """)
        msg_box.exec_()
        
    def show_version_error(self, required_version):
        """显示版本不匹配错误，并关闭程序"""
        error_message = f"当前客户端版本不匹配\n\n服务器要求的版本: {required_version}\n当前客户端版本: {ChatClient.CLIENT_VERSION}\n\n请更新客户端后再试。"
        
        msg_box = QMessageBox()
        msg_box.setWindowTitle("版本不匹配")
        msg_box.setText(error_message)
        msg_box.setIcon(QMessageBox.Critical)
        msg_box.setStyleSheet("""
            QMessageBox { background-color: #2c2c2c; color: #ffffff; min-width: 400px; }
            QLabel { color: #ffffff; font-size: 14px; padding: 10px; }
            QPushButton { background-color: #555555; color: #ffffff; border: none; padding: 10px 20px; border-radius: 6px; font-size: 14px; min-height: 32px; }
            QPushButton:hover { background-color: #666666; }
            QPushButton:pressed { background-color: #777777; }
        """)
        msg_box.exec_()
        
        # 版本不匹配时，退出程序
        sys.exit(0)
        

    
    def closeEvent(self, event):
        """关闭窗口事件处理，使用暗黑模式样式的确认对话框"""
        msg_box = QMessageBox()
        msg_box.setWindowTitle("确认")
        msg_box.setText("确定要退出吗？")
        msg_box.setIcon(QMessageBox.Question)
        msg_box.setStandardButtons(QMessageBox.Yes | QMessageBox.No)
        msg_box.setDefaultButton(QMessageBox.No)
        msg_box.setStyleSheet("""
            QMessageBox { background-color: #2c2c2c; color: #ffffff; min-width: 350px; }
            QLabel { color: #ffffff; font-size: 14px; padding: 10px; }
            QPushButton { background-color: #555555; color: #ffffff; border: none; padding: 10px 20px; border-radius: 6px; font-size: 14px; min-height: 32px; }
            QPushButton:hover { background-color: #666666; }
            QPushButton:pressed { background-color: #777777; }
        """)
        
        if msg_box.exec_() == QMessageBox.Yes:
            self.client.disconnect()
            event.accept()
        else:
            event.ignore()

class UserInfoDialog(QDialog):
    """用户信息对话框"""
    def __init__(self, username, ip_address, parent=None):
        super().__init__(parent)
        self.setWindowTitle(f"用户信息 - {username}")
        self.setMinimumWidth(320)  # 增加窗口最小宽度
        
        # 创建布局
        layout = QVBoxLayout(self)
        layout.setSpacing(20)  # 增加布局间距
        layout.setContentsMargins(20, 20, 20, 20)  # 增加边距
        
        # 显示用户头像（这里用用户名首字母代替）
        avatar_label = QLabel()
        avatar_label.setAlignment(Qt.AlignCenter)
        avatar_label.setStyleSheet("background-color: #4CAF50; color: white; font-size: 48px; font-weight: bold; border-radius: 60px; width: 120px; height: 120px;")
        avatar_label.setText(username[0].upper())
        
        # 用户信息，增加IP地址显示
        info_label = QLabel()
        info_label.setAlignment(Qt.AlignCenter)
        info_label.setText(f"用户名: {username}\n在线状态: 在线\nIP地址: {ip_address}")
        info_label.setStyleSheet("color: #ffffff; font-size: 14px; line-height: 1.6;")
        
        # 关闭按钮
        close_button = QPushButton("关闭")
        close_button.clicked.connect(self.accept)
        close_button.setStyleSheet("background-color: #555555; color: white; border: none; padding: 10px 24px; border-radius: 6px; font-size: 14px;")
        close_button.setMinimumHeight(36)  # 增加按钮高度
        
        # 创建按钮容器布局，使按钮居中
        button_container = QHBoxLayout()
        button_container.setAlignment(Qt.AlignCenter)
        button_container.addWidget(close_button)
        
        layout.addWidget(avatar_label, alignment=Qt.AlignCenter)
        layout.addWidget(info_label)
        layout.addLayout(button_container)
        
        # 设置暗黑模式样式
        self.setStyleSheet(".QDialog {background-color: #2c2c2c;}")

if __name__ == "__main__":
    app = QApplication(sys.argv)
    window = ChatWindow()
    window.show()
    sys.exit(app.exec_())