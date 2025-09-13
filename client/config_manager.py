#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
配置管理器模块
负责保存和加载客户端配置信息，如服务器地址、端口和用户名
"""
import os
import json

class ConfigManager:
    """配置管理器类，用于处理客户端配置的保存和加载"""
    def __init__(self):
        # 确定配置文件路径
        self.config_file = os.path.join(
            os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
            'client_config.json'
        )
        # 初始化默认配置
        self.default_config = {
            'server_host': 'localhost',
            'server_port': 7995,
            'username': ''
        }
        # 确保配置文件存在
        self._ensure_config_file_exists()
    
    def _ensure_config_file_exists(self):
        """确保配置文件存在，如果不存在则创建并写入默认配置"""
        if not os.path.exists(self.config_file):
            try:
                # 确保配置文件所在目录存在
                os.makedirs(os.path.dirname(self.config_file), exist_ok=True)
                # 写入默认配置
                with open(self.config_file, 'w', encoding='utf-8') as f:
                    json.dump(self.default_config, f, ensure_ascii=False, indent=4)
            except Exception as e:
                print(f"创建配置文件失败: {e}")
    
    def _load_config(self):
        """加载配置文件内容"""
        try:
            with open(self.config_file, 'r', encoding='utf-8') as f:
                return json.load(f)
        except (json.JSONDecodeError, IOError) as e:
            print(f"加载配置文件失败: {e}")
            # 返回默认配置
            return self.default_config
    
    def _save_config(self, config):
        """保存配置到文件"""
        try:
            with open(self.config_file, 'w', encoding='utf-8') as f:
                json.dump(config, f, ensure_ascii=False, indent=4)
            return True
        except Exception as e:
            print(f"保存配置文件失败: {e}")
            return False
    
    def get_server_info(self):
        """获取保存的服务器信息（主机地址和端口）
        
        Returns:
            tuple: (host, port) - 服务器地址和端口号
        """
        config = self._load_config()
        return (
            config.get('server_host', self.default_config['server_host']),
            config.get('server_port', self.default_config['server_port'])
        )
    
    def get_username(self):
        """获取保存的用户名
        
        Returns:
            str: 保存的用户名，如果没有保存则返回空字符串
        """
        config = self._load_config()
        return config.get('username', self.default_config['username'])
    
    def save_config(self, server_host, server_port, username):
        """保存配置信息
        
        Args:
            server_host (str): 服务器主机地址
            server_port (int): 服务器端口号
            username (str): 用户名
        
        Returns:
            bool: 保存是否成功
        """
        config = {
            'server_host': server_host,
            'server_port': server_port,
            'username': username
        }
        return self._save_config(config)