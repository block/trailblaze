<#-- Shared log card partial for Trailblaze logs -->
<div class="col" style="margin-bottom:10px;">
    <div class="card shadow-sm">
        <div style="position: relative;">
            <#assign logColors = {
            "MAESTRO_DRIVER": "lightblue",
            "MAESTRO_COMMAND": "mediumpurple",
            "TRAILBLAZE_COMMAND": "lightyellow",
            "LLM_REQUEST": "lightgreen",
            "AGENT_TASK_STATUS": "lightgray",
            "SESSION_STATUS": "lightseagreen",
            "OBJECTIVE_START": "#f5b042",
            "OBJECTIVE_COMPLETE": "#b5e07a",
            "TOP_LEVEL_MAESTRO_COMMAND": "#6ec1e4"
            }>
            <#assign logColor = logColors[log.type]!"#000000"> <!-- fallback color -->
            <div style="height: 5px; background-color: ${logColor};"></div>
            <span class="text-center"
                  style="display: block; font-size: .6em; font-weight: bold; background-color: ${logColor};">
                 <#if log.type == "MAESTRO_DRIVER">
                     <span>${log.type} (${log.action.class.simpleName})</span>
                    <#else>
                     <span>${log.type}</span>
                 </#if>
            </span>

            <#if log.screenshotFile??>
                <div style="position: relative;">
                    <img id="screenshot-${log_index}"
                         src="${log.screenshotFile}"
                         class="bd-placeholder-img card-img"
                         style="object-fit:contain; border: 1px solid black;" alt="Screenshot">
                </div>
            </#if>
            <div class="p-1">
                <#if log.type == "TOP_LEVEL_MAESTRO_COMMAND">
                    <pre class="small"><b>Top-Level Maestro Command:</b> ${log.command?html}</pre>
                <#elseif log.type == "MAESTRO_COMMAND">
                    <pre class="small">${log.asMaestroYaml()?html}</pre>
                <#elseif log.type == "TRAILBLAZE_COMMAND">
                    <pre class="small">${log.asCommandJson()?html}</pre>
                <#elseif log.type == "AGENT_TASK_STATUS">
                    <h6 class="font-weight-bold">${log.agentTaskStatus.class.simpleName}</h6>
                    <pre class="small">${log.agentTaskStatus.statusData.prompt?html}</pre>
                <#elseif log.type == "SESSION_STATUS">
                    <p>${log.sessionStatus?html}</p>
                <#elseif log.type == "OBJECTIVE_START">
                    <b>Objective Start:</b>
                    <pre class="small">${log.description?html}</pre>
                <#elseif log.type == "OBJECTIVE_COMPLETE">
                    <b>Objective Complete:</b>
                    <pre class="small">${log.description?html}</pre>
                    <#if log.objectiveResult??>
                        <b>Result:</b>
                        <pre class="small">${log.objectiveResult.llmExplanation?html}</pre>
                    </#if>
                </#if>
            </div>
            <#if log.type == "MAESTRO_DRIVER">
                <#if log.action.x?? && log.action.y??>
                    <script>
                        drawScreenshotCircle(${log_index}, ${log.deviceWidth?c}, ${log.deviceHeight?c}, ${log.action.x?c}, ${log.action.y?c});
                    </script>
                </#if>
            </#if>
        </div>
        <div class="card-body">
            <p class="card-text">
                <strong>Time Elapsed:</strong>
                <span>${(log.timestamp - logs[0].timestamp)?number_to_date?string("mm:ss")}</span>
            </p>
            <div class=" text-center">
                <button type="button" class="btn btn-primary" data-bs-toggle="modal"
                        data-bs-target="#logModal${log_index}">
                    View Details
                </button>
            </div>
            <!-- Modal -->
            <div class="modal fade" id="logModal${log_index}" tabindex="-1"
                 aria-labelledby="logModalLabel${log_index}" aria-hidden="true">
                <div class="modal-dialog modal-lg">
                    <div class="modal-content">
                        <div class="modal-header">
                            <h5 class="modal-title" id="logModalLabel${log_index}">Log Details</h5>
                            <button type="button" class="close" data-bs-dismiss="modal"
                                    aria-label="Close">
                                <span aria-hidden="true">&times;</span>
                            </button>
                        </div>
                        <div class="modal-body">
                            <#if log.type == 'MAESTRO_DRIVER'>
                                <pre>${log.action.class.simpleName}</pre>
                                <pre>${log.debugString()?html}</pre>
                            <#elseif log.type == 'LLM_REQUEST'>
                                <h5>LLM Response</h5>
                                <pre>${log.llmResponse[0]?html}</pre>
                                <h5>Request Duration</h5>
                                <pre>${(log.duration)?number_to_date?string("mm:ss")}</pre>
                                <#if log.llmMessage??>
                                    <h5>LLM Message</h5>
                                    <pre>${log.llmMessage?html}</pre>
                                </#if>
                                <!-- Collapsible Chat History -->
                                <button class="btn btn-outline-secondary mb-2" type="button" data-bs-toggle="collapse"
                                        data-bs-target="#chatHistory${log_index}" aria-expanded="false"
                                        aria-controls="chatHistory${log_index}">
                                    Show Full Chat History
                                </button>
                                <div class="collapse" id="chatHistory${log_index}">
                                    <div class="card card-body" style="max-height: 400px; overflow-y: auto;">
                                        <#if log.llmMessages?? && (log.llmMessages?size > 0)>
                                            <#list log.llmMessages as message>
                                                <#if message.message??>
                                                <#-- Remove base64 screenshot blocks from message -->
                                                    <#assign cleanMessage = message.message?replace('data:image/png;base64,[A-Za-z0-9+/=]+', '[screenshot removed]', 'r')>
                                                    <div class="mb-2">
                                                        <span class="badge bg-secondary">${message.role?cap_first}</span>
                                                        <div class="border rounded p-2 mt-1"
                                                             style="background-color: <#if message.role == 'user'>#e3f2fd<#elseif message.role == 'assistant'>#f1f8e9<#else>#f8bbd0</#if>;">
                                                            <pre style="white-space: pre-wrap; word-break: break-word; margin: 0;">${cleanMessage?html}</pre>
                                                        </div>
                                                    </div>
                                                </#if>
                                            </#list>
                                        <#else>
                                            <div class="text-muted">No chat history available.</div>
                                        </#if>
                                    </div>
                                </div>
                                <h5>Actions Returned</h5>
                                <#list log.actions as action>
                                    <pre>${action?html}</pre>
                                </#list>
                            <#elseif log.type == 'OBJECTIVE_START'>
                                <h5>Objective Start</h5>
                                <pre>${log.description?html}</pre>
                            <#elseif log.type == 'OBJECTIVE_COMPLETE'>
                                <h5>Objective Complete</h5>
                                <pre>${log.description?html}</pre>
                                <#if log.objectiveResult??>
                                    <h5>Result</h5>
                                    <pre>${log.objectiveResult.llmExplanation?html}</pre>
                                    <#if log.objectiveResult.statusData?? && log.objectiveResult.statusData.prompt??>
                                        <h5>Prompt</h5>
                                        <pre>${log.objectiveResult.statusData.prompt?html}</pre>
                                    </#if>
                                </#if>
                            <#else>
                                <pre>${log?html}</pre>
                            </#if>
                        </div>
                        <div class="modal-footer">
                            <button type="button" class="btn btn-secondary" data-bs-dismiss="modal">
                                Close
                            </button>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    </div>
</div>
