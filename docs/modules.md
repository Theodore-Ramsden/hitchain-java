以太坊java版本的代码大概分以下几个部分：

1.config 主要是代码初始化相关代码，在程序运行的时候最先 被调用；

2.core 定义了区块头、区块、账户状态、交易执行池、交易状态等基本信息，同时也定义了区块链和状态管理类

3.crypto 定义了hash加密算法等基本操作

4.datasource 定义了数据库（levelDB）、cache以及相关操作的类

5.db 定义了数据仓库，数据管理等类

6.facade 定义了对外暴露的接口

7.jsonrpc 对外提供rpc服务接口

8.listener 监听特定的事件

9.manager 定义了区块读取，全局管理的类

10.mine 挖矿的相关操作都在这个包

11.net 网络相关在这个包里，具体包括网络节点发现，消息的发送，以及区块消息的接收和发送；

12.solidity solidity代码的编译等

13.sync 数据同步相关代码在这里

14.trie 默克尔树相关数据结构和操作

15.validator 所有验证的工作

16.vm 底层的vm操作类