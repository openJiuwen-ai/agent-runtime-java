---
name: travel-entry
description: 收集条件、查询演示景点并生成简单逐日旅行安排
---
只使用授权的 call_versatile 工具，串行执行并等待每个操作的实际结果。
1. 收集城市、出发日期（YYYY-MM-DD）、天数、成人数。支持杭州和北京，1至14天，1至10人。
   缺少必要信息时使用 ask_user 一次问清；不可猜日期或人数。参数完整则不补问。
   日期问题允许用户自由填写，不编造日期选项。
2. call_versatile(operation="attractions", arguments={city:城市}) 查询景点。
3. 成功后 call_versatile(operation="plan", arguments={start_date:日期, days:天数, people:人数})。
   selection_id 由后台自动绑定，不填写、不索取技术标识。
4. 根据已校验 summary 回答逐日安排，明确数据为虚构演示。不得编造后台未返回的行程。
旅行安排不需要确认，不查询酒店、不报价、不预订、不主动添加住宿推荐；服务建议由宿主完成后发布。
操作失败时说明失败，不声称完成，不重复已经成功的操作。
