// 初始化助手
window.X_Assistant?.init?.({
appName: "你的应用ID",
agentId: "你的专家ID",
getIdToken: () => { return "用户的idToken" }, // 提供一个获取当前用户一事通token的方法 必需
layoutType: "domMount", // doim
});

// 手动执行挂载
window.X_Assistant?.render("mountId"); // 保证有挂载点 <div id="mountId" />

// 获取session任务列表
const { jsSdk } = window.X_Assistant?.getTools();
const res = await jsSdk?.getSessionList?.();
setSessions(res?.sessions || []);

// 删除session任务，删除任务后，可以自行重新获取列表
await jsSdk?.deleteSession?.(sessionId);

// 获取技能列表
const skillList = await jsSdk?.getSkillList?.();

// 切换session任务
const { changeSessionId } = window.X_Assistant?.getTools();
changeSessionId(sessionId); // sessionId为空时，则会切换到新建对话
// 新建对话
changeSessionId('');

// 监听新建对话事件，获得新对话产生的sessionId
window.X_Assistant.on("ai:createNewSession", async (data: any) => {
console.log(data.sessionId);
});

// 以下为较为完整的react组件接入示例
const [sessions, setSessions] = useState([]);
const [sessionId, setSessionId] = useState();

useEffect(() => {
// 初始化sdk
window.X_Assistant?.init?.({
appName: "LD96.13_X-WEB-SDK_expert-mkt-CerzYiXC",
agentId: "expert-mkt-CerzYiXC",
getIdToken: () => sessionStorage.getItem("idToken"),
xPlatformEnv: 'x-test-new', // 测试环境如果是在https://x-test-new.paas.cmbchina.cn中注册的专家，需要添加这个配置
layoutType: "domMount",
});
// 监听新建对话
window.X_Assistant.on("ai:createNewSession", async (data: any) => {
if (data.sessionId) {
await getSessions();
setSessionId(data.sessionId);
}
});
getSessions();
window.X_Assistant.render("mountId");
return () => {
window.X_Assistant?.destroy?.();
};
}, []);

const getSessions = async () => {
const {jsSdk} = window.X_Assistant?.getTools();
const res = await jsSdk?.getSessionList?.();
setSessions(res?.sessions || []);
};

const onChangeSessionId = (id: string) => {
setSessionId(id || undefined);
const {changeSessionId} = window.X_Assistant?.getTools();
changeSessionId?.(id);
};

const onDelete = async (id: string) => {
const {jsSdk} = window.X_Assistant?.getTools();
await jsSdk?.deleteSession?.(id);
await getSessions();
if (sessionId === id) {
setSessionId(undefined);
const {changeSessionId} = window.X_Assistant?.getTools();
changeSessionId?.("");
}
};

return (
<div>
<Button onClick={() => onChangeSessionId("")} >新建对话</Button>
{
<!--自己渲染列表-->
sessions.map((session) => {
return (
<div onClick={() => onChangeSessionId(session.uid)}>
{ session.title }
<Button onClick={() => onDelete(session.uid)} >删除</Button>
</div>
)
})
}
<!--对话内容挂载区域-->
<div id="mountId" />
</div>
)