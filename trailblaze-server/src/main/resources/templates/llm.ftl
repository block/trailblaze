<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Logs Viewer</title>
    <link href="https://getbootstrap.com/docs/5.3/dist/css/bootstrap.min.css" rel="stylesheet">
    <script src="https://cdn.jsdelivr.net/npm/marked/marked.min.js"></script>
    <script src="https://getbootstrap.com/docs/5.3/dist/js/bootstrap.bundle.min.js"></script>
    <style>
        body {
            display: flex;
            flex-direction: column;
            height: 100vh;
            transition: background-color 0.3s, color 0.3s;
        }

        .chat-container {
            flex-grow: 1;
            overflow-y: auto;
            padding: 1rem;
        }

        .message-group {
            display: flex;
            flex-direction: column;
            margin-bottom: 1rem;
        }

        .message-label {
            font-size: 0.8em;
            margin-bottom: 0.2rem;
            font-weight: bold;
        }

        .user-group {
            align-items: flex-end;
        }

        .system-group {
            align-items: flex-start;
        }

        .chat-message {
            max-width: 80%;
            padding: 0.5rem 1rem;
            border-radius: 1rem;
            position: relative;
        }

        .user-message {
            background-color: #007bff;
            color: white;
        }

        .system-message {
            background-color: #f1f3f5;
            color: black;
        }
    </style>
</head>
<body class="bg-light">
<div class="container-fluid p-3 bg-white rounded-lg shadow-sm">
    <#include "*/header.ftl">
    <div class="row">
        <div class="col-md-6">
            <h1 class="display-4 fw-bold">LLM Recording</h1>
        </div>
    </div>
    <hr/>

    <div class="container d-flex flex-column flex-grow-1">
        <#if llmMessages??>
            <div class="chat-container">
                <#list llmMessages as message>
                    <#if message.message??>
                        <#if message.role == "user">
                            <div class="message-group user-group">
                                <div class="message-label">User</div>
                                <div class="chat-message user-message">
                                    <div class="markdown">${message.message}</div>
                                    <#if message.functionCall??>
                                        <img id="screenshot-${message_index}"
                                             src="data:image/png;base64,${message.base64EncodedScreenshot}"
                                             class="border"
                                             style="object-fit:contain; max-height:400px;max-width:400px;"
                                             alt="Screenshot">
                                    </#if>
                                </div>
                            </div>
                        </#if>
                        <#if message.role == "system">
                            <div class="message-group system-group">
                                <div class="message-label">System</div>
                                <div class="chat-message system-message">
                                    <div class="row">
                                        <div class="col-md-8">
                                            <div class="markdown">${message.message}</div>

                                            <#if message.functionCall??>
                                                <hr/>
                                                <h4>Function Call:</h4>
                                                <div class="markdown">${message.functionCall}</div>
                                            </#if>
                                        </div>
                                    </div>
                                </div>
                            </div>
                        </#if>

                        <#if message.role == "assistant">
                            <div class="message-group system-group">
                                <div class="message-label">Assistant</div>
                                <div class="chat-message system-message">
                                    <div class="row">
                                        <div class="col-md-8">
                                            <div class="markdown">${message.message}</div>

                                            <#if message.functionCall??>
                                                <hr/>
                                                <h4>Function Call:</h4>
                                                <div class="markdown">${message.functionCall}</div>
                                            </#if>
                                        </div>
                                    </div>
                                </div>
                            </div>
                        </#if>
                    </#if>
                </#list>

            </div>
        <#else>
            <div class="col">
                <h4>No LLM logs available for Session "${session}"</h4>
            </div>
        </#if>
    </div>

    <script>
        // Convert all elements with class 'markdown' to rendered markdown
        document.addEventListener('DOMContentLoaded', function () {
            document.querySelectorAll('.markdown').forEach(function (codeElement) {
                console.log("Rendering Markdown " + codeElement.textContent)
                codeElement.innerHTML = marked.parse(codeElement.textContent);
            });
        });
    </script>
</div>
</body>
</html>
