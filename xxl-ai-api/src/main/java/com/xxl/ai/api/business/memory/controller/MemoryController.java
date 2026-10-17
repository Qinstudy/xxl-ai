package com.xxl.ai.api.business.memory.controller;

import com.xxl.ai.api.business.memory.model.dto.MemoryDTO;
import com.xxl.ai.api.business.memory.service.MemoryService;
import com.xxl.ai.api.business.space.model.SpaceContext;
import com.xxl.ai.api.business.space.service.SpaceService;
import com.xxl.sso.core.annotation.XxlSso;
import com.xxl.tool.response.PageModel;
import com.xxl.tool.response.Response;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 长期记忆 Controller（B端管理）：按业务空间查看 Agent×访客的长期记忆（对话管理菜单下的「长期记忆」）
 *
 * @author xxl-ai 2026-10-17
 */
@RestController
@RequestMapping("/memory")
public class MemoryController {

    @Resource
    private MemoryService memoryService;
    @Resource
    private SpaceService spaceService;

    /**
     * 分页查询空间内记忆列表（可按 Agent、访客ID 过滤）
     */
    @RequestMapping("/pageList")
    @XxlSso(permission = "memory:default")
    public Response<PageModel<MemoryDTO>> pageList(HttpServletRequest request,
                                                   @RequestHeader(value = "xxl-space-id", required = false) Integer spaceId,
                                                   @RequestParam(required = false, defaultValue = "0") long agentId,
                                                   @RequestParam(required = false, defaultValue = "0") int offset,
                                                   @RequestParam(required = false, defaultValue = "10") int pagesize,
                                                   String visitorId) {
        Response<SpaceContext> spaceResp = spaceService.checkSpace(request, spaceId);
        if (!spaceResp.isSuccess()) {
            return Response.ofFail(spaceResp.getMsg());
        }
        PageModel<MemoryDTO> pageModel = memoryService.pageList(spaceResp.getData().getSpaceId(), agentId, offset, pagesize, visitorId);
        return Response.ofSuccess(pageModel);
    }

    /**
     * 修改记忆内容
     */
    @RequestMapping("/update")
    @XxlSso(permission = "memory:default")
    public Response<String> update(HttpServletRequest request,
                                   @RequestHeader(value = "xxl-space-id", required = false) Integer spaceId,
                                   @RequestBody MemoryDTO body) {
        Response<SpaceContext> spaceResp = spaceService.checkSpace(request, spaceId);
        if (!spaceResp.isSuccess()) {
            return Response.ofFail(spaceResp.getMsg());
        }
        return memoryService.update(spaceResp.getData().getSpaceId(), body.getId(), body.getContent());
    }

    /**
     * 批量删除记忆
     */
    @RequestMapping("/delete")
    @XxlSso(permission = "memory:default")
    public Response<String> delete(HttpServletRequest request,
                                   @RequestHeader(value = "xxl-space-id", required = false) Integer spaceId,
                                   @RequestBody List<Long> ids) {
        Response<SpaceContext> spaceResp = spaceService.checkSpace(request, spaceId);
        if (!spaceResp.isSuccess()) {
            return Response.ofFail(spaceResp.getMsg());
        }
        return memoryService.deleteByIds(ids);
    }

}
