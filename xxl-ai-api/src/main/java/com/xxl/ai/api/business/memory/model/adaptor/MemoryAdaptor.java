package com.xxl.ai.api.business.memory.model.adaptor;

import com.xxl.ai.api.business.memory.model.dto.MemoryDTO;
import com.xxl.ai.api.business.memory.model.entity.Memory;
import com.xxl.tool.core.DateTool;

/**
 * 长期记忆 适配器（实体 → DTO）
 *
 * @author xxl-ai 2026-10-17
 */
public class MemoryAdaptor {

    /**
     * 实体转 DTO（时间格式化为字符串）
     */
    public static MemoryDTO adapt2dto(Memory memory) {
        if (memory == null) {
            return null;
        }
        MemoryDTO dto = new MemoryDTO();
        dto.setId(memory.getId());
        dto.setAgentId(memory.getAgentId());
        dto.setVisitorId(memory.getVisitorId());
        dto.setContent(memory.getContent());
        dto.setLastMsgId(memory.getLastMsgId());
        dto.setAddTime(DateTool.formatDateTime(memory.getAddTime()));
        dto.setUpdateTime(DateTool.formatDateTime(memory.getUpdateTime()));
        return dto;
    }

}
