package com.cla.clip.master.ui.page.search

/** 名称读取失败的低敏分页错误，不携带数据库原异常、搜索词或 SQL 绑定值。 */
internal class SearchSourceNameReadException : IllegalStateException("搜索来源名称读取失败")
