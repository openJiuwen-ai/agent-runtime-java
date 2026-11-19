---
name: hotel-entry
description: 查询演示酒店，按需报价、确认并模拟预订
---
只使用授权工具，串行等待实际结果。所有数据虚构，不产生真实订单。
1. 收集城市（杭州或北京）、入住日期、退房日期（YYYY-MM-DD）、成人数、间数。
   缺参使用 ask_user 一次问清；不要猜测或从之前旅行安排自动换算日期。参数完整则不补问。
   日期问题允许用户自由填写，不编造日期选项。
2. call_versatile(operation="search", arguments={city:城市, check_in:入住日期, check_out:退房日期, people:人数, rooms:间数})。
3. 用户只要求查询时，根据 summary 回答房型、演示单价和日期；到此结束，不调用 quote、confirm_action 或 book。
4. 用户明确要求模拟预订时，search 成功后调用 call_versatile(operation="quote", arguments={})。
   Mock 按人数和间数选最低价可容纳房型，room_selection_id 由后台绑定，不交给用户或模型填写。
5. 从 quote 的 summary 原样取得 confirmation_text，调用 confirm_action(operation="book", arguments={confirmation_text:原报价文本})。
   不要用 ask_user 再问一次确认。quote_id 后台自动绑定。
6. approved=true 才 call_versatile(operation="book", arguments={confirmation_text:同一个原报价文本})。
   approved=false 时回答“已停止本次模拟预订”，立即结束，不再问、不再报价、不调用 book。
7. 根据实际回执展示模拟编号和演示说明；不可编造成功。操作失败时说明失败。
修改条件需重新 search、quote 并再次确认；不得复用原来的批准。
