package com.fastagent.web;

import com.fastagent.agent.AgentService;
import com.fastagent.auth.AuthSupport;
import com.fastagent.service.ApiException;
import com.fastagent.workspace.SessionInfo;
import com.fastagent.workspace.SessionService;
import com.fastagent.workspace.WorkspaceService;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 会话接口（工作空间下）：列表 / 新建 / 删除 / 会话级记录。
 *
 * <p>会话索引由 {@link SessionService} 维护（列表与标题）；
 * 对话正文由 AgentScope 按 {@code (userId, sessionId)} 持久化，
 * 这里通过 HarnessAgent 内部的 ReActAgent 取回。
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/sessions")
public class SessionController {

    private final WorkspaceService workspaces;
    private final SessionService sessions;
    private final AgentService agents;

    public SessionController(WorkspaceService workspaces, SessionService sessions, AgentService agents) {
        this.workspaces = workspaces;
        this.sessions = sessions;
        this.agents = agents;
    }

    @GetMapping
    public List<SessionInfo> list(@PathVariable String workspaceId, HttpServletRequest req) {
        String userId = AuthSupport.requireUserId(req);
        workspaces.require(userId, workspaceId);
        return sessions.list(userId, workspaceId);
    }

    @PostMapping
    public SessionInfo create(@PathVariable String workspaceId, HttpServletRequest req) {
        String userId = AuthSupport.requireUserId(req);
        workspaces.require(userId, workspaceId);
        return sessions.create(userId, workspaceId);
    }

    @DeleteMapping("/{sessionId}")
    public Map<String, Object> delete(@PathVariable String workspaceId,
                                      @PathVariable String sessionId,
                                      HttpServletRequest req) {
        String userId = AuthSupport.requireUserId(req);
        workspaces.require(userId, workspaceId);
        // 连 agent 侧的对话状态一并清掉，否则重建同名会话还能读到旧上下文。
        // 实例缓存键带模型，所以要用该会话记的那个模型去找它
        try {
            SessionInfo info = sessions.require(userId, workspaceId, sessionId);
            agents.agentFor(userId, workspaceId, info == null ? null : info.getModelId())
                    .clearContext(userId, sessionId);
        } catch (Exception e) {
            // 状态清理失败不应阻止索引删除
        }
        sessions.delete(userId, workspaceId, sessionId);
        return Map.of("ok", true);
    }

    /**
     * 记录该会话使用的模型 —— 输入框下方的模型选择走这里。
     *
     * <p>记进会话索引后，下次进入该会话即恢复；传空表示清除记录、回到该用户的默认模型。
     */
    @PostMapping("/{sessionId}/model")
    public SessionInfo setModel(@PathVariable String workspaceId,
                                @PathVariable String sessionId,
                                @RequestBody(required = false) Map<String, String> body,
                                HttpServletRequest req) {
        String userId = AuthSupport.requireUserId(req);
        workspaces.require(userId, workspaceId);
        String modelId = body == null ? null : body.get("modelId");
        SessionInfo s = sessions.setModel(userId, workspaceId, sessionId, modelId);
        if (s == null) {
            throw new ApiException(404, "会话不存在");
        }
        return s;
    }

    /** 会话级记录：该会话的完整对话 */
    @GetMapping("/{sessionId}/messages")
    public List<MessageView> messages(@PathVariable String workspaceId,
                                      @PathVariable String sessionId,
                                      HttpServletRequest req) {
        String userId = AuthSupport.requireUserId(req);
        workspaces.require(userId, workspaceId);

        List<MessageView> out = new ArrayList<>();
        // 对话状态按 (userId, sessionId) 存在工作空间里，与模型无关；
        // 但实例缓存键带模型，所以按该会话记的模型取实例
        SessionInfo info = sessions.require(userId, workspaceId, sessionId);
        HarnessAgent agent = agents.agentFor(userId, workspaceId,
                info == null ? null : info.getModelId());
        AgentState state = agent.getDelegate().getAgentState(userId, sessionId);
        if (state == null || state.getContext() == null) {
            return out;
        }
        for (Msg m : state.getContext()) {
            MessageView v = MessageView.of(m);
            if (v == null || v.content() == null || v.content().isBlank()) {
                continue;
            }
            // 一轮对话在上下文里是「多条 ASSISTANT + 中间的 TOOL」：模型输出一段 →
            // 调工具 → 再输出一段。把连续的 ASSISTANT 合并成一条，
            // 否则界面上一条回答会显示成好几个气泡（流式时却是拼成一条，两边不一致）。
            MessageView last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && "assistant".equals(last.role()) && "assistant".equals(v.role())) {
                out.set(out.size() - 1, last.merge(v));
            } else {
                out.add(v);
            }
        }
        return out;
    }
}
