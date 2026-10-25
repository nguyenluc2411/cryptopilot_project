-- T-114: the first Academy content, written for this project in Vietnamese. It is reference data that every
-- environment needs, so it is a versioned migration; administrators edit it afterwards through the console.
-- Every lesson ends by saying that it teaches and does not advise.

INSERT INTO academy_topic (topic_id, slug, name, description, sort_order, created_at, updated_at)
VALUES
    (gen_random_uuid(), 'nhap-mon-crypto', 'Nhập môn crypto',
     'Tiền mã hóa là gì, khác gì tiền thông thường và vì sao thị trường này biến động mạnh.', 1,
     TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00'),
    (gen_random_uuid(), 'blockchain-cong-nghe', 'Blockchain & công nghệ',
     'Sổ cái phân tán, khối, cơ chế đồng thuận và những khái niệm kỹ thuật nằm sau mỗi giao dịch.', 2,
     TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00'),
    (gen_random_uuid(), 'vi-bao-mat', 'Ví & bảo mật',
     'Cách giữ tài sản số an toàn: ví, khóa riêng, cụm từ khôi phục và các chiêu lừa đảo thường gặp.', 3,
     TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00'),
    (gen_random_uuid(), 'giao-dich-spot', 'Giao dịch Spot',
     'Mua bán trực tiếp trên sàn: cặp giao dịch, sổ lệnh, các loại lệnh và phí.', 4,
     TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00'),
    (gen_random_uuid(), 'futures-don-bay', 'Futures & đòn bẩy',
     'Hợp đồng vĩnh cửu, ký quỹ, đòn bẩy, giá thanh lý và funding rate.', 5,
     TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00'),
    (gen_random_uuid(), 'phan-tich-ky-thuat', 'Phân tích kỹ thuật',
     'Đọc biểu đồ nến, xác định xu hướng, hỗ trợ, kháng cự và dùng chỉ báo một cách có kỷ luật.', 6,
     TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00'),
    (gen_random_uuid(), 'quan-ly-rui-ro', 'Quản lý rủi ro & tâm lý',
     'Bảo vệ vốn trước khi nghĩ đến lợi nhuận: khối lượng lệnh, stop-loss, nhật ký và tâm lý.', 7,
     TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00'),
    (gen_random_uuid(), 'defi-nft-stablecoin', 'DeFi, NFT & Stablecoin',
     'Những mảng ứng dụng lớn của blockchain ngoài việc mua bán coin.', 8,
     TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00');

-- ---------------------------------------------------------------------------------------------------------------
-- Lessons
-- ---------------------------------------------------------------------------------------------------------------

INSERT INTO academy_article (article_id, topic_id, slug, title, summary, body, article_level, reading_minutes,
                             article_status, sort_order, path_order, app_link, published_at, created_at, updated_at)
SELECT gen_random_uuid(), topic_id, 'tien-ma-hoa-la-gi', 'Tiền mã hóa là gì?',
       'Tiền mã hóa là tài sản số được ghi trên một sổ cái công khai thay vì trong hệ thống của một ngân hàng. Bài này giải thích nó hoạt động ra sao, khác tiền pháp định ở đâu và vì sao giá biến động mạnh.',
       $md$## Một định nghĩa ngắn

Tiền mã hóa (cryptocurrency, gọi tắt là crypto) là một loại tài sản số. Quyền sở hữu nó không do ngân hàng ghi chép mà được lưu trên một **sổ cái công khai** mà hàng nghìn máy tính trên thế giới cùng giữ một bản sao. Sổ cái đó thường là một blockchain.

Chữ "mã hóa" đến từ mật mã học: mỗi người giữ một **khóa riêng** để ký giao dịch, và cả mạng lưới kiểm tra được chữ ký đó là thật mà không cần biết khóa.

## Khác gì tiền trong tài khoản ngân hàng?

| | Tiền pháp định (VND, USD) | Tiền mã hóa |
|---|---|---|
| Ai phát hành | Ngân hàng trung ương | Giao thức phần mềm, theo luật lập trình sẵn |
| Ai ghi sổ | Ngân hàng | Mạng lưới máy tính phân tán |
| Giờ hoạt động | Theo giờ làm việc | 24/7, kể cả ngày lễ |
| Đảo ngược giao dịch | Có thể khiếu nại | Gần như không thể |

Điểm cuối cùng rất quan trọng: gửi nhầm địa chỉ thì thường **không lấy lại được**.

## Vì sao giá biến động mạnh?

- Thị trường còn trẻ, vốn hóa nhỏ hơn nhiều so với cổ phiếu hay vàng, nên một dòng tiền lớn đủ làm giá thay đổi nhanh.
- Giá phần lớn do kỳ vọng quyết định; tin tức, quy định pháp lý và tâm lý đám đông tác động rất mạnh.
- Nhiều người dùng đòn bẩy, khiến các đợt thanh lý dây chuyền khuếch đại biến động.

Biến động hai chiều: tăng nhanh thì cũng có thể giảm nhanh. Người mới nên bắt đầu bằng việc **hiểu rủi ro** trước khi nghĩ đến lợi nhuận.

## Bắt đầu từ đâu?

1. Đọc tiếp lộ trình "Người mới bắt đầu" của Academy theo thứ tự.
2. Quan sát thị trường thật trên trang Thị trường mà chưa cần bỏ tiền.
3. Luyện tập bằng giao dịch mô phỏng trước khi cân nhắc bất kỳ quyết định thật nào.

> *Nội dung chỉ nhằm mục đích giáo dục, không phải lời khuyên đầu tư.*
$md$,
       'BEGINNER', 5, 'PUBLISHED', 1, 1, '/markets',
       TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00'
  FROM academy_topic WHERE slug = 'nhap-mon-crypto';

INSERT INTO academy_article (article_id, topic_id, slug, title, summary, body, article_level, reading_minutes,
                             article_status, sort_order, path_order, app_link, published_at, created_at, updated_at)
SELECT gen_random_uuid(), topic_id, 'bitcoin-ethereum-va-altcoin', 'Bitcoin, Ethereum và altcoin',
       'Bitcoin là đồng tiền mã hóa đầu tiên, Ethereum là nền tảng chạy ứng dụng, còn altcoin là mọi đồng khác. Hiểu vai trò của từng nhóm giúp bạn đọc thị trường tỉnh táo hơn.',
       $md$## Bitcoin: "vàng kỹ thuật số"

Bitcoin (BTC) ra đời năm 2009, là blockchain công khai đầu tiên vận hành thành công. Đặc điểm nổi bật:

- **Nguồn cung giới hạn 21 triệu BTC**, được lập trình sẵn và không ai in thêm được.
- Khoảng bốn năm một lần, phần thưởng cho thợ đào giảm một nửa (sự kiện **halving**), nên tốc độ phát hành mới chậm dần.
- Mục tiêu chính là lưu trữ và chuyển giá trị, không phải chạy ứng dụng phức tạp.

Vì là đồng lớn nhất, BTC thường dẫn dắt xu hướng chung: khi BTC giảm mạnh, phần lớn thị trường giảm theo.

## Ethereum: một "máy tính toàn cầu"

Ethereum (đồng ETH) cho phép viết **hợp đồng thông minh** — chương trình tự chạy trên blockchain. Nhờ đó mới có DeFi, NFT, stablecoin và hàng nghìn token khác. ETH được dùng để trả phí (gas) cho mỗi thao tác trên mạng.

Từ năm 2022, Ethereum chuyển từ đào bằng máy (Proof of Work) sang cơ chế đặt cọc (Proof of Stake), giảm mạnh điện năng tiêu thụ.

## Altcoin là gì?

Altcoin là mọi đồng không phải Bitcoin. Có thể chia thô thành:

- **Nền tảng blockchain khác** cạnh tranh với Ethereum về tốc độ hoặc phí.
- **Token ứng dụng** gắn với một dự án cụ thể (sàn phi tập trung, trò chơi, mạng lưu trữ...).
- **Stablecoin** neo giá theo USD.
- **Memecoin** ra đời từ trào lưu cộng đồng, thường không có công dụng rõ ràng và biến động cực mạnh.

## Lưu ý khi tìm hiểu một altcoin

1. Dự án giải quyết vấn đề gì, có người dùng thật không?
2. Ai nắm phần lớn nguồn cung? Có đợt mở khóa token lớn sắp tới không?
3. Thanh khoản trên sàn có đủ để mua bán mà không trượt giá nhiều?

Phần lớn altcoin từng nổi tiếng đã mất gần hết giá trị theo thời gian. Tên tuổi lớn hôm nay không bảo đảm cho ngày mai.

> *Nội dung chỉ nhằm mục đích giáo dục, không phải lời khuyên đầu tư.*
$md$,
       'BEGINNER', 6, 'PUBLISHED', 2, 2, '/markets',
       TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00'
  FROM academy_topic WHERE slug = 'nhap-mon-crypto';

INSERT INTO academy_article (article_id, topic_id, slug, title, summary, body, article_level, reading_minutes,
                             article_status, sort_order, path_order, app_link, published_at, created_at, updated_at)
SELECT gen_random_uuid(), topic_id, 'von-hoa-nguon-cung-tokenomics', 'Vốn hóa, nguồn cung và tokenomics',
       'Giá một đồng coin tự nó nói rất ít. Vốn hóa, nguồn cung lưu hành và lịch mở khóa token mới cho biết đồng đó lớn đến đâu và áp lực bán có thể đến từ đâu.',
       $md$## Giá thấp không có nghĩa là "rẻ"

Một đồng giá 0,01 USD không rẻ hơn một đồng giá 100 USD nếu đồng đầu tiên có số lượng lớn gấp hàng triệu lần. Để so sánh quy mô, người ta dùng **vốn hóa thị trường**:

> Vốn hóa = Giá × Nguồn cung đang lưu hành

## Ba con số nguồn cung

- **Nguồn cung lưu hành**: số token đang có trên thị trường.
- **Tổng cung**: số token đã được tạo ra, kể cả phần còn bị khóa.
- **Nguồn cung tối đa**: giới hạn trên mà giao thức cho phép (Bitcoin là 21 triệu; nhiều đồng không có giới hạn).

Khoảng cách lớn giữa nguồn cung lưu hành và tổng cung là tín hiệu cần chú ý: token bị khóa sẽ dần được mở ra và có thể bị bán.

## Vốn hóa pha loãng hoàn toàn (FDV)

FDV = Giá × Nguồn cung tối đa (hoặc tổng cung). Nếu FDV lớn gấp nhiều lần vốn hóa hiện tại, giá hôm nay đang "gánh" kỳ vọng rằng thị trường sẽ hấp thụ được lượng token rất lớn trong tương lai.

## Tokenomics là gì?

Tokenomics là "kinh tế học của token": token được phân bổ cho ai (đội ngũ, quỹ đầu tư, cộng đồng), mở khóa theo lịch nào, có cơ chế đốt bớt hay phát hành thêm không, và token dùng để làm gì trong hệ sinh thái.

Một số câu hỏi nên đặt ra:

1. Đội ngũ và quỹ nắm bao nhiêu phần trăm? Bao giờ họ được bán?
2. Lạm phát hằng năm là bao nhiêu?
3. Người dùng có lý do thật để giữ token không, hay chỉ để đầu cơ?

## Khối lượng giao dịch và thanh khoản

Khối lượng giao dịch 24 giờ cho biết đồng đó được mua bán sôi động đến đâu. Thanh khoản thấp nghĩa là một lệnh lớn có thể đẩy giá đi xa — vừa khó mua, vừa khó bán khi cần.

> *Nội dung chỉ nhằm mục đích giáo dục, không phải lời khuyên đầu tư.*
$md$,
       'BEGINNER', 6, 'PUBLISHED', 3, NULL, '/markets',
       TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00'
  FROM academy_topic WHERE slug = 'nhap-mon-crypto';

INSERT INTO academy_article (article_id, topic_id, slug, title, summary, body, article_level, reading_minutes,
                             article_status, sort_order, path_order, app_link, published_at, created_at, updated_at)
SELECT gen_random_uuid(), topic_id, 'blockchain-hoat-dong-the-nao', 'Blockchain hoạt động như thế nào?',
       'Blockchain là chuỗi các khối dữ liệu được nối với nhau bằng mã băm, do nhiều máy tính cùng lưu giữ. Bài này đi qua hành trình của một giao dịch từ lúc bạn bấm gửi đến khi được xác nhận.',
       $md$## Hình dung một cuốn sổ chung

Thử tưởng tượng cả lớp cùng giữ một cuốn sổ thu chi giống hệt nhau. Ai muốn ghi thêm một dòng phải thông báo cho cả lớp; mọi người kiểm tra xem người đó có thật sự đủ tiền không rồi mới cùng chép vào. Muốn sửa một dòng cũ, bạn phải sửa sổ của đa số cả lớp cùng lúc — gần như bất khả thi.

Blockchain chính là cuốn sổ đó, chỉ khác là "cả lớp" gồm hàng nghìn máy tính (gọi là **node**) trên khắp thế giới.

## Khối và chuỗi

- Giao dịch được gom lại thành từng **khối** (block).
- Mỗi khối chứa **mã băm** (hash) của khối trước nó. Mã băm giống như dấu vân tay: thay đổi dù chỉ một ký tự trong dữ liệu thì dấu vân tay đổi hoàn toàn.
- Vì khối sau "khóa" vào khối trước, sửa một khối cũ sẽ làm hỏng toàn bộ chuỗi phía sau.

## Hành trình của một giao dịch

1. Ví của bạn tạo giao dịch và **ký** bằng khóa riêng.
2. Giao dịch được phát lên mạng và nằm chờ trong hàng đợi (mempool).
3. Thợ đào hoặc người xác thực chọn giao dịch, đóng vào khối mới; người trả phí cao thường được ưu tiên.
4. Khối được mạng lưới chấp nhận: giao dịch có **1 xác nhận**.
5. Mỗi khối mới nối thêm sau đó là một xác nhận nữa; càng nhiều xác nhận, giao dịch càng khó bị đảo ngược.

Với Bitcoin, một khối mới ra đời khoảng mỗi 10 phút. Nhiều blockchain khác nhanh hơn nhiều.

## Công khai nhưng không hẳn ẩn danh

Ai cũng xem được mọi giao dịch trên blockchain công khai qua trang **block explorer**. Địa chỉ ví không ghi tên bạn, nhưng một khi địa chỉ bị gắn với danh tính (chẳng hạn qua sàn có xác minh KYC), lịch sử giao dịch có thể bị lần ra.

> *Nội dung chỉ nhằm mục đích giáo dục, không phải lời khuyên đầu tư.*
$md$,
       'BEGINNER', 7, 'PUBLISHED', 1, 3, NULL,
       TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00'
  FROM academy_topic WHERE slug = 'blockchain-cong-nghe';

INSERT INTO academy_article (article_id, topic_id, slug, title, summary, body, article_level, reading_minutes,
                             article_status, sort_order, path_order, app_link, published_at, created_at, updated_at)
SELECT gen_random_uuid(), topic_id, 'co-che-dong-thuan-pow-pos', 'Cơ chế đồng thuận: PoW và PoS',
       'Không có ai đứng giữa, vậy ai quyết định khối nào hợp lệ? Proof of Work và Proof of Stake là hai cách phổ biến nhất để hàng nghìn máy tính thống nhất cùng một sự thật.',
       $md$## Bài toán đồng thuận

Trong mạng phân tán, mỗi node có thể nhận giao dịch theo thứ tự khác nhau, thậm chí có node cố tình gian lận. **Cơ chế đồng thuận** là bộ luật giúp cả mạng chọn ra cùng một phiên bản sổ cái, và khiến việc gian lận tốn kém hơn nhiều so với làm đúng.

## Proof of Work (bằng chứng công việc)

- Thợ đào dùng máy chuyên dụng giải một câu đố toán học bằng cách thử hàng tỷ phép băm.
- Ai tìm ra lời giải trước được quyền tạo khối mới và nhận phần thưởng.
- Muốn viết lại lịch sử, kẻ tấn công cần nắm hơn một nửa sức mạnh tính toán của cả mạng (**tấn công 51%**), cực kỳ tốn kém với mạng lớn như Bitcoin.

Nhược điểm: tiêu thụ nhiều điện năng và cần phần cứng đắt tiền.

## Proof of Stake (bằng chứng cổ phần)

- Người xác thực (validator) **đặt cọc** token của mình để có quyền đề xuất và xác nhận khối.
- Người được chọn đề xuất khối phụ thuộc vào lượng token đặt cọc và yếu tố ngẫu nhiên.
- Gian lận sẽ bị **phạt cắt cọc** (slashing): mất một phần hoặc toàn bộ số token đã đặt.

Ưu điểm là tiết kiệm năng lượng; điểm cần cân nhắc là quyền lực có thể tập trung vào những ai nắm nhiều token.

## Staking là gì?

Người dùng phổ thông có thể tham gia PoS bằng cách **staking**: khóa token để góp phần bảo vệ mạng và nhận phần thưởng. Rủi ro gồm: giá token giảm, thời gian chờ rút dài, và rủi ro của bên trung gian nếu bạn stake qua sàn hoặc giao thức khác.

## So sánh nhanh

| | PoW | PoS |
|---|---|---|
| Tài nguyên bảo vệ mạng | Điện năng, phần cứng | Token đặt cọc |
| Ví dụ | Bitcoin | Ethereum (từ 2022) |
| Hình phạt gian lận | Tốn điện mà không được thưởng | Bị cắt cọc |

> *Nội dung chỉ nhằm mục đích giáo dục, không phải lời khuyên đầu tư.*
$md$,
       'INTERMEDIATE', 7, 'PUBLISHED', 2, NULL, NULL,
       TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00'
  FROM academy_topic WHERE slug = 'blockchain-cong-nghe';

INSERT INTO academy_article (article_id, topic_id, slug, title, summary, body, article_level, reading_minutes,
                             article_status, sort_order, path_order, app_link, published_at, created_at, updated_at)
SELECT gen_random_uuid(), topic_id, 'phi-gas-va-layer-2', 'Phí gas và Layer 2',
       'Mỗi thao tác trên blockchain đều tốn phí. Bài này giải thích phí gas được tính thế nào, vì sao có lúc tăng vọt, và Layer 2 giúp giảm phí ra sao.',
       $md$## Phí gas là gì?

Trên Ethereum và nhiều mạng tương tự, mỗi thao tác — chuyển token, đổi coin trên sàn phi tập trung, đúc NFT — đều cần máy tính của mạng lưới xử lý. **Gas** là đơn vị đo lượng công việc đó, và bạn trả phí gas bằng đồng gốc của mạng (ví dụ ETH).

Phí thực trả ≈ lượng gas cần dùng × giá mỗi đơn vị gas.

- Thao tác càng phức tạp thì càng tốn nhiều gas.
- Giá gas do cung cầu quyết định: lúc mạng đông người dùng, giá gas tăng.

## Vì sao giao dịch bị kẹt?

Nếu bạn đặt giá gas quá thấp lúc mạng tắc nghẽn, giao dịch có thể nằm chờ rất lâu. Ngược lại, giao dịch thất bại (ví dụ do trượt giá vượt giới hạn) **vẫn mất phí gas** vì mạng đã tốn công xử lý.

## Layer 1 và Layer 2

- **Layer 1** là blockchain gốc, nơi giao dịch được chốt cuối cùng (Bitcoin, Ethereum...).
- **Layer 2** là mạng xây bên trên Layer 1: xử lý nhiều giao dịch bên ngoài, rồi gửi bản tóm tắt về Layer 1. Nhờ chia sẻ chi phí cho nhiều giao dịch, phí trên Layer 2 thường thấp hơn đáng kể.

Một dạng Layer 2 phổ biến là **rollup**: gom hàng trăm giao dịch thành một gói và ghi gói đó lên Layer 1.

## Cầu nối (bridge) và rủi ro

Để chuyển tài sản giữa các mạng, người ta dùng **cầu nối**. Đây từng là nơi xảy ra nhiều vụ hack lớn nhất trong lịch sử crypto. Trước khi dùng một cầu nối, hãy tìm hiểu nó đã hoạt động bao lâu, được kiểm toán chưa, và luôn thử trước với số tiền nhỏ.

## Mẹo thực tế

1. Kiểm tra đúng mạng trước khi gửi: gửi token qua sai mạng có thể làm mất tài sản.
2. Luôn giữ một ít đồng gốc của mạng trong ví để trả phí.
3. Thao tác không gấp thì chọn lúc mạng vắng để tiết kiệm phí.

> *Nội dung chỉ nhằm mục đích giáo dục, không phải lời khuyên đầu tư.*
$md$,
       'INTERMEDIATE', 6, 'PUBLISHED', 3, NULL, NULL,
       TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00'
  FROM academy_topic WHERE slug = 'blockchain-cong-nghe';

INSERT INTO academy_article (article_id, topic_id, slug, title, summary, body, article_level, reading_minutes,
                             article_status, sort_order, path_order, app_link, published_at, created_at, updated_at)
SELECT gen_random_uuid(), topic_id, 'vi-khoa-rieng-seed-phrase', 'Ví crypto, khóa riêng và cụm từ khôi phục',
       'Ví crypto không chứa coin mà chứa chìa khóa. Ai giữ khóa riêng hoặc cụm từ khôi phục là người làm chủ tài sản. Bài này giúp bạn chọn loại ví phù hợp và giữ chìa khóa an toàn.',
       $md$## Ví không "chứa" coin

Coin luôn nằm trên blockchain. Thứ ví của bạn giữ là **khóa riêng** (private key) — chuỗi bí mật dùng để ký giao dịch, chứng minh bạn có quyền tiêu số coin gắn với một **địa chỉ**.

- **Địa chỉ ví** giống số tài khoản: có thể đưa cho người khác để nhận tiền.
- **Khóa riêng** giống mật khẩu kiêm chữ ký: tuyệt đối không đưa cho ai.

## Cụm từ khôi phục (seed phrase)

Hầu hết ví hiện nay tạo ra một dãy 12 hoặc 24 từ tiếng Anh. Từ dãy này có thể tái tạo toàn bộ khóa riêng của ví. Mất điện thoại nhưng còn seed phrase thì vẫn khôi phục được ví; ngược lại, **ai có seed phrase của bạn là có toàn bộ tài sản của bạn**.

Nguyên tắc vàng:

1. Viết ra giấy hoặc khắc lên kim loại; cất ở nơi an toàn, có thể chia hai nơi.
2. Không chụp ảnh, không lưu trong ghi chú điện thoại, email hay dịch vụ đám mây.
3. Không bao giờ nhập seed phrase vào một trang web hay gửi cho "nhân viên hỗ trợ". Không bộ phận hỗ trợ hợp pháp nào cần nó.

## Các loại ví

| Loại | Ví dụ | Ưu điểm | Rủi ro |
|---|---|---|---|
| Ví trên sàn (lưu ký) | Tài khoản sàn giao dịch | Dễ dùng, quên mật khẩu vẫn lấy lại được | Sàn giữ khóa thay bạn; sàn sập hoặc bị hack là mất |
| Ví nóng (tự lưu ký) | Ứng dụng ví trên điện thoại, tiện ích trình duyệt | Bạn tự giữ khóa, dùng tiện | Thiết bị nhiễm mã độc có thể lộ khóa |
| Ví lạnh (phần cứng) | Thiết bị chuyên dụng | Khóa không bao giờ rời thiết bị | Tốn chi phí, phải tự bảo quản |

Câu nói quen thuộc trong cộng đồng: *"Not your keys, not your coins"* — không giữ khóa thì coin chưa thật sự là của bạn.

## Thói quen an toàn

- Bật xác thực hai lớp (2FA) bằng ứng dụng, hạn chế dùng SMS.
- Kiểm tra kỹ địa chỉ trước khi gửi; mã độc có thể tráo địa chỉ trong bộ nhớ tạm.
- Gửi thử một khoản nhỏ khi chuyển tới địa chỉ mới.

> *Nội dung chỉ nhằm mục đích giáo dục, không phải lời khuyên đầu tư.*
$md$,
       'BEGINNER', 7, 'PUBLISHED', 1, 4, NULL,
       TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00'
  FROM academy_topic WHERE slug = 'vi-bao-mat';

INSERT INTO academy_article (article_id, topic_id, slug, title, summary, body, article_level, reading_minutes,
                             article_status, sort_order, path_order, app_link, published_at, created_at, updated_at)
SELECT gen_random_uuid(), topic_id, 'nhan-dien-lua-dao', 'Nhận diện các chiêu lừa đảo phổ biến',
       'Thị trường crypto thu hút nhiều kẻ lừa đảo vì giao dịch khó đảo ngược. Bài này liệt kê những chiêu thường gặp và các dấu hiệu giúp bạn dừng lại kịp lúc.',
       $md$## Vì sao crypto là "mảnh đất màu mỡ" cho lừa đảo?

Giao dịch không thể hoàn tác, ví không cần danh tính và thị trường đầy những câu chuyện làm giàu nhanh. Kẻ gian khai thác đúng ba điều đó: **sự vội vàng, lòng tham và sự cả tin**.

## Những chiêu thường gặp

### 1. Hứa lợi nhuận cố định, cao bất thường
"Lãi 2% mỗi ngày", "cam kết hoàn vốn". Không ai bảo đảm được lợi nhuận trong thị trường biến động. Đây thường là mô hình Ponzi: lấy tiền người sau trả cho người trước, đến khi không còn người mới thì sụp đổ.

### 2. Giả mạo người nổi tiếng hoặc sàn giao dịch
Tài khoản mạng xã hội, kênh chat hay trang web giả mạo, kêu gọi "gửi 1 nhận 2", hoặc thông báo bạn trúng thưởng và cần "xác minh ví".

### 3. Trang web và ứng dụng giả (phishing)
Đường dẫn nhìn gần giống trang thật, chỉ khác một ký tự. Mục tiêu là lấy mật khẩu, mã 2FA hoặc seed phrase của bạn.

### 4. "Hỗ trợ viên" tự nhắn tin
Bạn hỏi trong một nhóm cộng đồng, lập tức có người nhắn riêng xưng là hỗ trợ. Hỗ trợ thật không chủ động nhắn riêng và không bao giờ xin seed phrase.

### 5. Ký hợp đồng độc hại
Một trang web yêu cầu bạn "kết nối ví" rồi ký một giao dịch cấp quyền chi tiêu không giới hạn. Sau đó kẻ gian có thể rút sạch token khỏi ví.

### 6. Token rút thảm (rug pull)
Dự án mới tung hô rầm rộ, giá tăng vọt, rồi đội ngũ rút hết thanh khoản và biến mất.

### 7. Lừa đảo tình cảm, "chuyên gia" dẫn dắt
Làm quen lâu dài qua mạng, sau đó dẫn dắt bạn vào một nền tảng đầu tư do chính họ dựng lên. Ban đầu bạn rút được ít tiền để tin, đến khi nạp lớn thì không rút được nữa.

## Dấu hiệu cần dừng lại

- Bị thúc giục: "chỉ còn hôm nay", "suất cuối cùng".
- Lợi nhuận được hứa trước hoặc "không rủi ro".
- Yêu cầu seed phrase, khóa riêng, mã 2FA hoặc chuyển tiền để "mở khóa" tài khoản.
- Nền tảng lạ, không rõ pháp nhân, chỉ liên hệ qua ứng dụng chat.

Khi nghi ngờ, hãy dừng lại, tự tìm trang chính thức bằng công cụ tìm kiếm thay vì bấm vào đường dẫn được gửi, và hỏi người bạn tin tưởng.

> *Nội dung chỉ nhằm mục đích giáo dục, không phải lời khuyên đầu tư.*
$md$,
       'BEGINNER', 6, 'PUBLISHED', 2, 5, '/community',
       TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00'
  FROM academy_topic WHERE slug = 'vi-bao-mat';

INSERT INTO academy_article (article_id, topic_id, slug, title, summary, body, article_level, reading_minutes,
                             article_status, sort_order, path_order, app_link, published_at, created_at, updated_at)
SELECT gen_random_uuid(), topic_id, 'san-giao-dich-cap-giao-dich-so-lenh', 'Sàn giao dịch, cặp giao dịch và sổ lệnh',
       'Trước khi đặt lệnh đầu tiên, bạn cần đọc được màn hình giao dịch: cặp BTC/USDT nghĩa là gì, sổ lệnh cho biết điều gì và chênh lệch giá mua bán ảnh hưởng thế nào.',
       $md$## Sàn tập trung và sàn phi tập trung

- **Sàn tập trung (CEX)** do một công ty vận hành, giữ tài sản của khách và khớp lệnh trên máy chủ của mình. Dễ dùng, thanh khoản cao, nhưng bạn phải tin tưởng công ty đó.
- **Sàn phi tập trung (DEX)** chạy bằng hợp đồng thông minh; bạn giao dịch trực tiếp từ ví của mình. Tự chủ hơn, nhưng phải tự chịu trách nhiệm về bảo mật và phí gas.

## Đọc một cặp giao dịch

Trong cặp **BTC/USDT**:

- **BTC** là tài sản cơ sở — thứ bạn mua hoặc bán.
- **USDT** là tài sản định giá — thứ bạn dùng để trả.

Giá 60.000 nghĩa là 1 BTC đổi được 60.000 USDT. Mua BTC/USDT là bỏ USDT ra lấy BTC; bán là ngược lại.

## Sổ lệnh (order book)

Sổ lệnh là danh sách tất cả lệnh chờ:

- **Bên mua (bid)**: những người muốn mua, xếp từ giá cao xuống thấp.
- **Bên bán (ask)**: những người muốn bán, xếp từ giá thấp lên cao.

Giá mua cao nhất và giá bán thấp nhất gặp nhau ở giữa. Khoảng cách giữa hai giá đó là **chênh lệch (spread)**. Cặp có thanh khoản tốt thường có spread rất hẹp.

## Độ sâu thị trường và trượt giá

Nếu bạn mua một lượng lớn ngay lập tức, lệnh sẽ "ăn" dần các mức giá bán từ thấp lên cao. Giá khớp trung bình vì thế xấu hơn giá bạn nhìn thấy ban đầu — đó là **trượt giá (slippage)**. Sổ lệnh càng dày thì trượt giá càng ít.

## Maker và taker

- **Maker**: đặt lệnh chờ trong sổ, góp thêm thanh khoản.
- **Taker**: khớp ngay vào lệnh có sẵn, lấy đi thanh khoản.

Phí taker thường cao hơn phí maker. Với người giao dịch thường xuyên, chênh lệch phí nhỏ này cộng dồn thành con số đáng kể.

> *Nội dung chỉ nhằm mục đích giáo dục, không phải lời khuyên đầu tư.*
$md$,
       'BEGINNER', 6, 'PUBLISHED', 1, 6, '/markets',
       TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00'
  FROM academy_topic WHERE slug = 'giao-dich-spot';

INSERT INTO academy_article (article_id, topic_id, slug, title, summary, body, article_level, reading_minutes,
                             article_status, sort_order, path_order, app_link, published_at, created_at, updated_at)
SELECT gen_random_uuid(), topic_id, 'lenh-market-limit-stop', 'Các loại lệnh: market, limit và stop',
       'Lệnh market khớp ngay, lệnh limit chờ đúng giá, lệnh stop kích hoạt khi giá chạm ngưỡng. Hiểu rõ từng loại giúp bạn kiểm soát giá vào, giá ra và rủi ro.',
       $md$## Lệnh thị trường (Market)

Mua hoặc bán **ngay lập tức** theo giá tốt nhất đang có trong sổ lệnh.

- Ưu điểm: chắc chắn khớp.
- Nhược điểm: không kiểm soát được giá; thị trường biến động hoặc thanh khoản mỏng thì có thể trượt giá đáng kể.

Dùng khi tốc độ quan trọng hơn vài phần nghìn chênh lệch giá.

## Lệnh giới hạn (Limit)

Bạn đặt **mức giá mong muốn**; lệnh chỉ khớp ở giá đó hoặc tốt hơn.

- Lệnh mua limit đặt dưới giá hiện tại: chờ giá giảm xuống.
- Lệnh bán limit đặt trên giá hiện tại: chờ giá tăng lên.

Ưu điểm là kiểm soát giá và thường được hưởng phí maker; nhược điểm là có thể **không bao giờ khớp** nếu giá không chạm tới.

## Lệnh dừng (Stop)

Lệnh stop "ngủ" cho đến khi giá chạm **giá kích hoạt**, rồi mới gửi lệnh vào thị trường.

- **Stop-market**: chạm giá kích hoạt thì bán hoặc mua theo giá thị trường. Gần như chắc chắn khớp, nhưng có thể khớp xa giá kích hoạt khi giá rơi nhanh.
- **Stop-limit**: chạm giá kích hoạt thì đặt một lệnh limit. Kiểm soát được giá, nhưng nếu giá lướt qua quá nhanh thì lệnh có thể không khớp và bạn vẫn đang giữ vị thế.

Công dụng phổ biến nhất là **cắt lỗ (stop-loss)**: tự động thoát khi giá đi ngược dự tính.

## Chốt lời (Take profit) và OCO

- **Take profit** là lệnh đóng vị thế khi giá đạt mục tiêu lợi nhuận.
- **OCO (One Cancels the Other)** ghép một lệnh chốt lời và một lệnh cắt lỗ: lệnh này khớp thì lệnh kia tự hủy.

## Ví dụ

Bạn mua BTC ở 60.000 USDT, kế hoạch là chốt lời ở 66.000 và cắt lỗ ở 57.000. Một lệnh OCO gồm limit bán 66.000 và stop bán 57.000 giúp kế hoạch tự chạy mà không cần ngồi canh màn hình.

Hãy thử từng loại lệnh trong chế độ giao dịch mô phỏng trước để thấy chúng khớp như thế nào.

> *Nội dung chỉ nhằm mục đích giáo dục, không phải lời khuyên đầu tư.*
$md$,
       'BEGINNER', 7, 'PUBLISHED', 2, 7, '/trade/spot',
       TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00'
  FROM academy_topic WHERE slug = 'giao-dich-spot';

INSERT INTO academy_article (article_id, topic_id, slug, title, summary, body, article_level, reading_minutes,
                             article_status, sort_order, path_order, app_link, published_at, created_at, updated_at)
SELECT gen_random_uuid(), topic_id, 'hop-dong-vinh-cuu-la-gi', 'Hợp đồng tương lai vĩnh cửu là gì?',
       'Futures cho phép kiếm lời cả khi giá tăng lẫn khi giá giảm mà không cần sở hữu coin. Đổi lại, bạn phải hiểu vị thế long, short, ký quỹ và vì sao rủi ro cao hơn spot nhiều lần.',
       $md$## Từ spot đến futures

Trên thị trường **spot**, bạn mua coin thật và sở hữu nó. Trên thị trường **futures**, bạn mua bán một **hợp đồng** có giá đi theo giá coin; bạn không sở hữu coin, chỉ hưởng (hoặc chịu) phần chênh lệch giá.

Hợp đồng tương lai truyền thống có ngày đáo hạn. **Hợp đồng vĩnh cửu (perpetual)** — loại phổ biến nhất trong crypto — thì không có ngày đáo hạn; vị thế được giữ bao lâu tùy ý, miễn còn đủ ký quỹ.

## Long và short

- **Long**: đặt cược giá tăng. Giá tăng thì lời, giá giảm thì lỗ.
- **Short**: đặt cược giá giảm. Giá giảm thì lời, giá tăng thì lỗ.

Short cho phép kiếm lời khi thị trường đi xuống, nhưng về lý thuyết giá có thể tăng không giới hạn, nên khoản lỗ của short cũng không có trần nếu không có cơ chế dừng.

## Ký quỹ

Để mở vị thế, bạn đặt một khoản **ký quỹ (margin)** làm tài sản bảo đảm. Nhờ đòn bẩy, khoản ký quỹ nhỏ có thể mở được vị thế lớn hơn nhiều lần — cả lời lẫn lỗ đều được phóng đại theo.

- **Ký quỹ ban đầu**: số tiền cần có để mở vị thế.
- **Ký quỹ duy trì**: mức tối thiểu phải giữ; rơi xuống dưới mức này thì vị thế bị thanh lý.

## Giá đánh dấu (mark price)

Để tránh bị thanh lý oan vì một cú giật giá ngắn trên một sàn, sàn tính lãi lỗ chưa chốt và điều kiện thanh lý theo **giá đánh dấu**, là mức giá được làm mượt từ nhiều nguồn, thay vì chỉ dùng giá khớp gần nhất.

## Vì sao người mới nên thận trọng?

Futures không khó ở cách bấm nút, mà khó ở chỗ một sai lầm có thể xóa sạch ký quỹ trong vài phút. Trước khi dùng, hãy chắc chắn bạn hiểu đòn bẩy, giá thanh lý và funding rate (các bài tiếp theo trong chủ đề này).

> *Nội dung chỉ nhằm mục đích giáo dục, không phải lời khuyên đầu tư.*
$md$,
       'INTERMEDIATE', 7, 'PUBLISHED', 1, NULL, '/markets',
       TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00'
  FROM academy_topic WHERE slug = 'futures-don-bay';

INSERT INTO academy_article (article_id, topic_id, slug, title, summary, body, article_level, reading_minutes,
                             article_status, sort_order, path_order, app_link, published_at, created_at, updated_at)
SELECT gen_random_uuid(), topic_id, 'don-bay-ky-quy-gia-thanh-ly', 'Đòn bẩy, ký quỹ và giá thanh lý',
       'Đòn bẩy phóng đại cả lời lẫn lỗ. Bài này tính thử một ví dụ cụ thể để thấy giá thanh lý gần đến mức nào khi đòn bẩy tăng, và cách giữ khoảng cách an toàn.',
       $md$## Đòn bẩy hoạt động thế nào?

Với đòn bẩy 10x, 100 USDT ký quỹ mở được vị thế trị giá 1.000 USDT. Giá đi đúng hướng 1% thì bạn lời khoảng 10 USDT — tức 10% trên ký quỹ. Nhưng giá đi ngược 1% thì bạn cũng mất 10% ký quỹ.

> Lãi/lỗ trên ký quỹ ≈ Biến động giá × Đòn bẩy

## Giá thanh lý gần đến mức nào?

Bỏ qua phí và ký quỹ duy trì để dễ hình dung, vị thế long sẽ mất toàn bộ ký quỹ khi giá giảm khoảng **1 / đòn bẩy**:

| Đòn bẩy | Giá đi ngược khoảng | Ví dụ với BTC ở 60.000 |
|---|---|---|
| 2x | 50% | ~30.000 |
| 5x | 20% | ~48.000 |
| 10x | 10% | ~54.000 |
| 50x | 2% | ~58.800 |
| 100x | 1% | ~59.400 |

Trên thực tế, sàn thanh lý **sớm hơn** mức trên, vì bạn phải giữ ký quỹ duy trì và còn phí giao dịch, phí thanh lý. Với BTC, biến động 2% trong một ngày là chuyện thường; đòn bẩy 50x vì thế cực kỳ mong manh.

## Ký quỹ cô lập và ký quỹ chéo

- **Cô lập (isolated)**: mỗi vị thế chỉ dùng phần ký quỹ riêng của nó. Bị thanh lý thì chỉ mất phần đó.
- **Chéo (cross)**: mọi vị thế dùng chung số dư của tài khoản. Vị thế khó bị thanh lý hơn, nhưng nếu bị thanh lý thì có thể mất nhiều hơn dự tính.

Người mới thường được khuyên dùng ký quỹ cô lập để giới hạn thiệt hại.

## Bậc ký quỹ duy trì

Vị thế càng lớn thì tỷ lệ ký quỹ duy trì sàn yêu cầu càng cao, và đòn bẩy tối đa càng thấp. Đó là lý do cùng mức đòn bẩy nhưng vị thế lớn lại có giá thanh lý gần hơn.

## Giữ khoảng cách an toàn

1. Đặt **stop-loss trước giá thanh lý** một khoảng đủ xa. Lý tưởng là bạn chủ động thoát lệnh vì kế hoạch sai, chứ không phải bị sàn thanh lý.
2. Chọn đòn bẩy từ mức rủi ro bạn chấp nhận được, không phải từ mức lợi nhuận bạn mong muốn.
3. Tính trước giá thanh lý của mỗi kế hoạch. Trình lập kế hoạch giao dịch của ứng dụng hiển thị giá này cho từng kế hoạch.

> *Nội dung chỉ nhằm mục đích giáo dục, không phải lời khuyên đầu tư.*
$md$,
       'INTERMEDIATE', 8, 'PUBLISHED', 2, NULL, '/plans/new',
       TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00'
  FROM academy_topic WHERE slug = 'futures-don-bay';

INSERT INTO academy_article (article_id, topic_id, slug, title, summary, body, article_level, reading_minutes,
                             article_status, sort_order, path_order, app_link, published_at, created_at, updated_at)
SELECT gen_random_uuid(), topic_id, 'funding-rate-open-interest', 'Funding rate và open interest',
       'Funding rate giữ giá hợp đồng vĩnh cửu bám sát giá spot, còn open interest cho biết có bao nhiêu tiền đang nằm trong các vị thế. Đọc hai chỉ số này cùng nhau giúp nhận ra lúc thị trường quá đông một phía.',
       $md$## Vì sao cần funding rate?

Hợp đồng vĩnh cửu không có ngày đáo hạn, nên không có thời điểm nào buộc giá hợp đồng quay về giá spot. **Funding rate** là cơ chế kéo hai giá lại gần nhau: định kỳ (thường mỗi 8 giờ, tùy sàn và cặp), một bên trả cho bên kia một khoản nhỏ.

- Funding **dương**: giá hợp đồng cao hơn spot, phe long trả cho phe short.
- Funding **âm**: giá hợp đồng thấp hơn spot, phe short trả cho phe long.

Khoản này trao đổi trực tiếp giữa người giao dịch với nhau, không phải phí sàn thu.

## Funding ảnh hưởng đến bạn ra sao?

Khoản funding được tính trên **giá trị vị thế**, không phải trên ký quỹ. Ví dụ: vị thế long 10.000 USDT, funding +0,01% mỗi kỳ, ba kỳ một ngày thì mất khoảng 3 USDT mỗi ngày. Nghe nhỏ, nhưng nếu giữ vị thế hàng tuần với đòn bẩy cao, funding có thể ăn mòn đáng kể lợi nhuận.

## Đọc funding như một chỉ báo tâm lý

- Funding dương cao kéo dài: rất nhiều người đang long, thị trường có thể đang hưng phấn quá mức.
- Funding âm sâu: phe short chiếm ưu thế, tâm lý bi quan.

Khi một phía quá đông, một cú biến động ngược nhỏ có thể kích hoạt thanh lý dây chuyền — gọi là **long squeeze** hoặc **short squeeze**.

## Open interest (OI)

Open interest là tổng giá trị các hợp đồng **đang mở** chưa đóng. Khác với khối lượng giao dịch (đếm mọi lượt mua bán), OI cho biết có bao nhiêu tiền đang "nằm" trong thị trường phái sinh.

Một vài cách đọc thường gặp (chỉ là gợi ý, không phải quy luật):

| Giá | Open interest | Cách hiểu thường gặp |
|---|---|---|
| Tăng | Tăng | Tiền mới vào, xu hướng tăng có lực |
| Tăng | Giảm | Phe short đóng vị thế, đà tăng có thể yếu dần |
| Giảm | Tăng | Vị thế short mới mở, áp lực giảm |
| Giảm | Giảm | Phe long thoát hoặc bị thanh lý |

Ứng dụng cho phép đặt cảnh báo khi funding rate hoặc open interest thay đổi mạnh, để bạn không phải tự canh.

> *Nội dung chỉ nhằm mục đích giáo dục, không phải lời khuyên đầu tư.*
$md$,
       'ADVANCED', 6, 'PUBLISHED', 3, NULL, '/alerts',
       TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00'
  FROM academy_topic WHERE slug = 'futures-don-bay';

INSERT INTO academy_article (article_id, topic_id, slug, title, summary, body, article_level, reading_minutes,
                             article_status, sort_order, path_order, app_link, published_at, created_at, updated_at)
SELECT gen_random_uuid(), topic_id, 'doc-bieu-do-nen', 'Đọc biểu đồ nến',
       'Mỗi cây nến kể lại cuộc giằng co giữa người mua và người bán trong một khoảng thời gian. Bài này hướng dẫn đọc giá mở, đóng, cao, thấp và chọn khung thời gian phù hợp.',
       $md$## Một cây nến gồm những gì?

Mỗi cây nến tóm tắt giá trong một **khung thời gian** (1 phút, 1 giờ, 1 ngày...) bằng bốn con số, gọi tắt là **OHLC**:

- **Open**: giá mở cửa đầu khung.
- **High**: giá cao nhất trong khung.
- **Low**: giá thấp nhất trong khung.
- **Close**: giá đóng cửa cuối khung.

Phần **thân** nến nối giá mở và giá đóng. Hai **râu** (bóng) kéo đến giá cao nhất và thấp nhất.

- Nến **xanh**: giá đóng cao hơn giá mở — phe mua thắng thế trong khung đó.
- Nến **đỏ**: giá đóng thấp hơn giá mở — phe bán thắng thế.

## Râu nến nói gì?

- Râu dưới dài: giá từng bị bán xuống sâu nhưng người mua đã kéo lên lại.
- Râu trên dài: giá từng tăng cao nhưng bị bán xuống.
- Thân rất nhỏ, hai râu dài: hai phe giằng co, thị trường lưỡng lự.

## Vài mẫu nến quen thuộc

- **Doji**: giá mở và đóng gần như bằng nhau — sự do dự.
- **Búa (hammer)**: thân nhỏ ở trên, râu dưới dài, thường xuất hiện sau một đợt giảm.
- **Nhấn chìm (engulfing)**: thân nến sau bao trùm toàn bộ thân nến trước, ngược màu.

Mẫu nến chỉ là gợi ý về tâm lý trong ngắn hạn. Nó đáng tin hơn khi xuất hiện tại vùng hỗ trợ, kháng cự quan trọng và đi kèm khối lượng giao dịch tăng.

## Chọn khung thời gian

- Khung nhỏ (1–15 phút) nhiều "nhiễu", tín hiệu giả xuất hiện liên tục.
- Khung lớn (4 giờ, 1 ngày, 1 tuần) cho bức tranh xu hướng rõ ràng hơn.

Một thói quen tốt: xem **khung lớn** để xác định xu hướng chính, rồi mới xuống **khung nhỏ** để tìm điểm vào lệnh.

## Đừng quên khối lượng

Cột khối lượng bên dưới biểu đồ cho biết bao nhiêu tài sản đã được giao dịch trong mỗi nến. Giá tăng kèm khối lượng tăng thường đáng tin hơn giá tăng khi khối lượng teo tóp.

> *Nội dung chỉ nhằm mục đích giáo dục, không phải lời khuyên đầu tư.*
$md$,
       'BEGINNER', 6, 'PUBLISHED', 1, 8, '/markets',
       TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00'
  FROM academy_topic WHERE slug = 'phan-tich-ky-thuat';

INSERT INTO academy_article (article_id, topic_id, slug, title, summary, body, article_level, reading_minutes,
                             article_status, sort_order, path_order, app_link, published_at, created_at, updated_at)
SELECT gen_random_uuid(), topic_id, 'xu-huong-ho-tro-khang-cu', 'Xu hướng, hỗ trợ và kháng cự',
       'Hỗ trợ là vùng giá người mua thường xuất hiện, kháng cự là vùng người bán thường áp đảo. Kết hợp với xu hướng, đây là nền tảng của hầu hết các phương pháp phân tích kỹ thuật.',
       $md$## Xác định xu hướng

Cách đơn giản nhất là nhìn các **đỉnh** và **đáy**:

- **Xu hướng tăng**: đỉnh sau cao hơn đỉnh trước, đáy sau cao hơn đáy trước.
- **Xu hướng giảm**: đỉnh sau thấp hơn đỉnh trước, đáy sau thấp hơn đáy trước.
- **Đi ngang**: giá dao động trong một biên độ, không tạo đỉnh hay đáy mới rõ ràng.

Câu quen thuộc "xu hướng là bạn" nhắc rằng giao dịch thuận xu hướng của khung lớn thường dễ hơn đi ngược nó.

## Hỗ trợ và kháng cự

- **Hỗ trợ**: vùng giá mà trước đây giá nhiều lần giảm tới rồi bật lên, vì người mua thấy "rẻ".
- **Kháng cự**: vùng giá mà trước đây giá nhiều lần tăng tới rồi quay đầu, vì người bán chốt lời.

Đây là **vùng**, không phải một đường kẻ chính xác đến từng đồng. Vùng được chạm nhiều lần, trên khung thời gian lớn và kèm khối lượng lớn thì càng có ý nghĩa.

Các con số tròn (50.000, 100.000) cũng thường trở thành hỗ trợ hoặc kháng cự tâm lý.

## Đổi vai

Khi giá phá vỡ một kháng cự và đứng vững phía trên, vùng kháng cự cũ thường trở thành **hỗ trợ mới**, và ngược lại. Nhiều người chờ giá **quay lại kiểm tra** (retest) vùng vừa phá vỡ rồi mới vào lệnh, thay vì đuổi theo cây nến phá vỡ.

## Phá vỡ giả

Không phải lần phá vỡ nào cũng thật. Giá có thể vượt kháng cự trong chốc lát rồi rơi lại ngay — gọi là **phá vỡ giả** (fakeout). Một vài cách giảm rủi ro:

1. Chờ nến **đóng cửa** bên ngoài vùng, thay vì phản ứng khi giá vừa chạm.
2. Kiểm tra khối lượng: phá vỡ thật thường đi kèm khối lượng tăng.
3. Luôn đặt stop-loss phòng trường hợp mình sai.

## Dùng trong kế hoạch giao dịch

Hỗ trợ và kháng cự giúp trả lời ba câu hỏi của mọi kế hoạch: **vào ở đâu, cắt lỗ ở đâu, chốt lời ở đâu**. Trang phân tích của ứng dụng tự đánh dấu các vùng hỗ trợ, kháng cự gần nhất để bạn tham khảo.

> *Nội dung chỉ nhằm mục đích giáo dục, không phải lời khuyên đầu tư.*
$md$,
       'INTERMEDIATE', 7, 'PUBLISHED', 2, NULL, '/markets',
       TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00'
  FROM academy_topic WHERE slug = 'phan-tich-ky-thuat';

INSERT INTO academy_article (article_id, topic_id, slug, title, summary, body, article_level, reading_minutes,
                             article_status, sort_order, path_order, app_link, published_at, created_at, updated_at)
SELECT gen_random_uuid(), topic_id, 'ema-rsi-macd', 'Ba chỉ báo phổ biến: EMA, RSI và MACD',
       'Chỉ báo kỹ thuật là phép tính trên giá quá khứ, giúp nhìn xu hướng và động lượng rõ hơn. Bài này giải thích ý nghĩa của EMA, RSI và MACD cùng những cái bẫy khi dùng chúng.',
       $md$## Chỉ báo là gì và không là gì

Chỉ báo được tính từ giá và khối lượng **đã xảy ra**. Nó giúp tóm tắt thông tin, không dự đoán được tương lai. Phần lớn chỉ báo đi **trễ** so với giá.

## EMA – đường trung bình động hàm mũ

EMA là giá trung bình của N nến gần nhất, trong đó nến gần đây được tính trọng số cao hơn.

- Giá nằm trên EMA và EMA dốc lên: thiên về xu hướng tăng.
- Hai EMA thường được dùng cùng nhau, ví dụ EMA ngắn và EMA dài. EMA ngắn cắt lên EMA dài là **giao cắt vàng**; cắt xuống là **giao cắt tử thần**.

Trong thị trường đi ngang, các đường EMA cắt nhau liên tục và tạo ra nhiều tín hiệu sai.

## RSI – chỉ số sức mạnh tương đối

RSI dao động từ 0 đến 100, đo tốc độ và độ lớn của các nhịp tăng so với nhịp giảm gần đây (thường dùng 14 nến).

- Trên 70: thường được coi là **quá mua**.
- Dưới 30: thường được coi là **quá bán**.

Cái bẫy lớn nhất: trong xu hướng tăng mạnh, RSI có thể nằm trên 70 rất lâu. Quá mua không có nghĩa là giá sắp giảm. Nhiều người dùng RSI để tìm **phân kỳ**: giá tạo đỉnh mới nhưng RSI thì không — dấu hiệu động lượng đang yếu đi.

## MACD

MACD được dựng từ chênh lệch giữa hai EMA (thường là 12 và 26), cùng một **đường tín hiệu** là EMA 9 của chính MACD.

- MACD cắt lên đường tín hiệu: động lượng tăng mạnh lên.
- MACD cắt xuống: động lượng suy yếu.
- **Histogram** thể hiện khoảng cách giữa hai đường; cột ngắn dần cho thấy động lượng đang chậm lại.

## Dùng chỉ báo cho đúng

1. Không chạy theo một chỉ báo đơn lẻ; tìm sự **đồng thuận** giữa xu hướng, vùng giá và chỉ báo.
2. Ít mà hiểu sâu tốt hơn chất đầy biểu đồ.
3. Kiểm chứng trên dữ liệu quá khứ và bằng giao dịch mô phỏng trước khi tin vào một quy tắc.

Ứng dụng cho phép đặt cảnh báo theo RSI, giao cắt EMA và giao cắt MACD, để bạn được báo khi điều kiện xảy ra thay vì ngồi canh.

> *Nội dung chỉ nhằm mục đích giáo dục, không phải lời khuyên đầu tư.*
$md$,
       'INTERMEDIATE', 8, 'PUBLISHED', 3, NULL, '/alerts',
       TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00'
  FROM academy_topic WHERE slug = 'phan-tich-ky-thuat';

INSERT INTO academy_article (article_id, topic_id, slug, title, summary, body, article_level, reading_minutes,
                             article_status, sort_order, path_order, app_link, published_at, created_at, updated_at)
SELECT gen_random_uuid(), topic_id, 'quan-ly-von-stop-loss', 'Quản lý vốn, khối lượng lệnh và stop-loss',
       'Người giao dịch lâu năm không thắng mọi lệnh; họ chỉ thua ít khi sai. Bài này hướng dẫn tính khối lượng lệnh từ mức rủi ro chấp nhận được và đặt stop-loss có lý do.',
       $md$## Bảo vệ vốn trước tiên

Mất 50% tài khoản thì cần lãi 100% mới hòa vốn. Toán học đơn giản này cho thấy vì sao **tránh thua lỗ lớn** quan trọng hơn tìm kiếm lợi nhuận lớn.

| Lỗ | Cần lãi để hòa vốn |
|---|---|
| 10% | ~11% |
| 25% | ~33% |
| 50% | 100% |
| 75% | 300% |

## Quy tắc rủi ro theo phần trăm

Nhiều người áp dụng quy tắc: **mỗi lệnh chỉ rủi ro một phần nhỏ của tài khoản**, thường 1–2%. Với tài khoản 1.000 USDT và mức rủi ro 1%, mỗi lệnh sai chỉ mất tối đa khoảng 10 USDT. Thua mười lệnh liên tiếp vẫn còn khoảng 90% tài khoản.

## Tính khối lượng lệnh từ stop-loss

Thứ tự đúng là: **chọn điểm cắt lỗ trước, rồi mới tính khối lượng**.

> Khối lượng = Số tiền chấp nhận rủi ro ÷ Khoảng cách từ giá vào đến stop-loss

Ví dụ: tài khoản 1.000 USDT, rủi ro 1% (10 USDT). Mua ở 100, stop-loss ở 95 (cách 5). Khối lượng = 10 ÷ 5 = **2 đơn vị** (giá trị vị thế 200 USDT). Nếu stop-loss xa hơn, khối lượng phải nhỏ lại để rủi ro vẫn là 10 USDT.

## Đặt stop-loss ở đâu?

Stop-loss nên đặt ở nơi mà nếu giá chạm tới, **lý do vào lệnh của bạn không còn đúng nữa** — thường là ngay dưới vùng hỗ trợ (với lệnh mua) hoặc ngay trên vùng kháng cự (với lệnh bán). Đừng đặt theo số tiền bạn "tiếc", cũng đừng dời stop-loss ra xa khi giá đang tiến lại gần.

## Tỷ lệ lợi nhuận trên rủi ro (R:R)

Nếu mỗi lệnh rủi ro 1 phần và mục tiêu lời 2 phần (R:R = 1:2), bạn chỉ cần thắng khoảng hơn một phần ba số lệnh là đã có lãi trước phí. Tính R:R **trước** khi vào lệnh giúp loại bỏ những cơ hội không đáng.

## Đưa vào kế hoạch

Trình lập kế hoạch giao dịch của ứng dụng tính khối lượng, R:R và giá thanh lý từ vốn, mức rủi ro, điểm vào và stop-loss bạn nhập, đồng thời cảnh báo khi một thông số vượt ngưỡng an toàn.

> *Nội dung chỉ nhằm mục đích giáo dục, không phải lời khuyên đầu tư.*
$md$,
       'BEGINNER', 7, 'PUBLISHED', 1, 9, '/plans/new',
       TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00'
  FROM academy_topic WHERE slug = 'quan-ly-rui-ro';

INSERT INTO academy_article (article_id, topic_id, slug, title, summary, body, article_level, reading_minutes,
                             article_status, sort_order, path_order, app_link, published_at, created_at, updated_at)
SELECT gen_random_uuid(), topic_id, 'tam-ly-giao-dich-va-nhat-ky', 'Tâm lý giao dịch và nhật ký',
       'FOMO, sợ hãi và giao dịch trả thù làm hỏng nhiều kế hoạch tốt. Bài này nhận diện những cái bẫy cảm xúc phổ biến và cách dùng nhật ký giao dịch để học từ chính mình.',
       $md$## Kẻ thù lớn nhất là chính mình

Phần lớn sai lầm không đến từ thiếu kiến thức, mà từ việc **biết nhưng không làm**: biết phải cắt lỗ nhưng vẫn cố giữ, biết không nên đuổi giá nhưng vẫn mua đỉnh.

## Những cái bẫy cảm xúc thường gặp

- **FOMO** (sợ bỏ lỡ): thấy giá tăng mạnh, mọi người khoe lời, vội mua vào khi giá đã chạy xa.
- **FUD** (sợ hãi, nghi ngờ): bán tháo vì tin đồn mà chưa kiểm chứng.
- **Giao dịch trả thù**: vừa thua một lệnh, lập tức vào lệnh lớn hơn để "gỡ".
- **Tự tin thái quá**: thắng vài lệnh liên tiếp rồi tăng khối lượng, bỏ qua quy tắc.
- **Gồng lỗ**: không chịu cắt lỗ vì hy vọng giá sẽ quay lại.

## Kỷ luật bằng quy trình

1. **Lập kế hoạch trước khi vào lệnh**: điểm vào, stop-loss, mục tiêu, khối lượng. Nếu không viết ra được thì chưa nên vào.
2. **Giới hạn trong ngày**: ví dụ thua hai lệnh liên tiếp thì dừng giao dịch đến hôm sau.
3. **Tách cảm xúc khỏi nút bấm**: đặt sẵn lệnh stop-loss và chốt lời để kế hoạch tự chạy.

## Nhật ký giao dịch

Nhật ký là công cụ học tập hiệu quả nhất mà nhiều người bỏ qua. Mỗi lệnh nên ghi lại:

- Lý do vào lệnh, điểm vào, stop-loss, mục tiêu.
- Kết quả và lãi lỗ thực tế sau phí.
- Cảm xúc lúc vào và lúc thoát lệnh.
- Bài học rút ra: mình có làm đúng kế hoạch không?

Sau vài chục lệnh, hãy đọc lại. Bạn sẽ thấy những khuôn mẫu: khung giờ hay thua, loại setup hay thắng, những lần phá vỡ quy tắc. Đó là thông tin không cuốn sách nào cho bạn được.

## Đánh giá đúng một lệnh

Một lệnh **lời nhưng phá kế hoạch** vẫn là lệnh tồi, vì nó củng cố một thói quen xấu. Một lệnh **lỗ nhưng làm đúng kế hoạch** là lệnh tốt — chi phí bình thường của việc giao dịch.

Nhật ký giao dịch trong ứng dụng tự ghi lại các lệnh từ kế hoạch và cho phép thêm ghi chú, cảm xúc và bài học.

> *Nội dung chỉ nhằm mục đích giáo dục, không phải lời khuyên đầu tư.*
$md$,
       'BEGINNER', 6, 'PUBLISHED', 2, 10, '/journal',
       TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00'
  FROM academy_topic WHERE slug = 'quan-ly-rui-ro';

INSERT INTO academy_article (article_id, topic_id, slug, title, summary, body, article_level, reading_minutes,
                             article_status, sort_order, path_order, app_link, published_at, created_at, updated_at)
SELECT gen_random_uuid(), topic_id, 'luyen-tap-giao-dich-mo-phong', 'Luyện tập bằng giao dịch mô phỏng',
       'Giao dịch mô phỏng dùng tiền ảo trên giá thị trường thật. Đây là cách rẻ nhất để học cách đặt lệnh, thử một chiến lược và rèn kỷ luật trước khi mạo hiểm bất kỳ đồng nào.',
       $md$## Giao dịch mô phỏng là gì?

Giao dịch mô phỏng (paper trading) cho bạn một tài khoản **tiền ảo** để mua bán theo **giá thị trường thật**. Lệnh được khớp như thật, có tính phí, có trượt giá, nhưng không có đồng tiền thật nào bị mất.

## Nên luyện điều gì?

1. **Thao tác**: đặt từng loại lệnh market, limit, stop; hiểu khi nào lệnh khớp và khi nào không.
2. **Quy trình**: lập kế hoạch → vào lệnh → quản lý vị thế → thoát lệnh → ghi nhật ký.
3. **Một chiến lược cụ thể**: chọn một bộ quy tắc rõ ràng và áp dụng nhất quán trong nhiều lệnh.
4. **Quản lý rủi ro**: giữ mức rủi ro mỗi lệnh như thể đó là tiền thật.

## Làm sao để luyện tập có ích?

- **Dùng số vốn ảo bằng số vốn bạn thật sự có**. Luyện với một triệu USD ảo khi thực tế bạn có vài trăm sẽ dạy sai cảm giác về khối lượng.
- **Đặt mục tiêu theo số lệnh**, ví dụ 30–50 lệnh, thay vì theo số ngày.
- **Ghi nhật ký đầy đủ** và đánh giá định kỳ: tỷ lệ thắng, R:R trung bình, mức sụt giảm lớn nhất.

## Giới hạn cần biết

Giao dịch mô phỏng **không tái tạo được áp lực tâm lý** của tiền thật. Nhiều người giao dịch rất kỷ luật với tiền ảo nhưng hoảng loạn khi có tiền thật. Kết quả mô phỏng tốt là điều kiện cần, không phải điều kiện đủ.

Ngoài ra, lệnh rất lớn trên thị trường thật có thể khớp kém hơn mô phỏng do ảnh hưởng tới sổ lệnh.

## Khi nào thì sẵn sàng?

Không có con số chung. Một dấu hiệu tốt là bạn đã có một quy trình được viết ra, làm theo nó nhất quán qua nhiều lệnh, và hiểu rõ mình thua vì đâu. Ứng dụng này chỉ hỗ trợ quyết định và mô phỏng; nó không đặt lệnh thật và không đưa ra lời khuyên đầu tư.

> *Nội dung chỉ nhằm mục đích giáo dục, không phải lời khuyên đầu tư.*
$md$,
       'BEGINNER', 5, 'PUBLISHED', 3, 11, '/trade/spot',
       TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00'
  FROM academy_topic WHERE slug = 'quan-ly-rui-ro';

INSERT INTO academy_article (article_id, topic_id, slug, title, summary, body, article_level, reading_minutes,
                             article_status, sort_order, path_order, app_link, published_at, created_at, updated_at)
SELECT gen_random_uuid(), topic_id, 'stablecoin-la-gi', 'Stablecoin là gì?',
       'Stablecoin là token được thiết kế để giữ giá ổn định, thường neo 1:1 với USD. Chúng là "đơn vị tiền" chính của thị trường crypto, nhưng không phải stablecoin nào cũng an toàn như nhau.',
       $md$## Vì sao cần stablecoin?

Giá BTC hay ETH có thể đổi vài phần trăm trong một giờ. Người giao dịch cần một thứ ổn định để **định giá, nghỉ giữa các lệnh và chuyển tiền nhanh** mà không rời khỏi blockchain. Stablecoin đóng vai trò đó: hầu hết cặp giao dịch lớn đều định giá bằng một stablecoin như USDT hoặc USDC.

## Các loại stablecoin

### 1. Bảo chứng bằng tài sản thật
Đơn vị phát hành giữ tiền mặt, trái phiếu ngắn hạn hoặc tài sản tương đương cho mỗi token phát ra. Mức độ tin cậy phụ thuộc vào việc dự trữ có đủ, có được kiểm toán và công bố minh bạch hay không.

### 2. Bảo chứng bằng crypto
Người dùng thế chấp crypto với giá trị **lớn hơn** số stablecoin vay ra (thế chấp vượt mức), để tạo khoảng đệm khi giá tài sản thế chấp giảm. Nếu tài sản thế chấp giảm quá sâu, vị thế bị thanh lý tự động.

### 3. Stablecoin thuật toán
Giữ giá bằng cơ chế cung cầu lập trình sẵn, không có (hoặc có rất ít) tài sản bảo chứng. Lịch sử đã có những stablecoin thuật toán mất neo và sụp đổ chỉ trong vài ngày, xóa sổ hàng chục tỷ USD.

## Rủi ro mất neo

"Ổn định" là mục tiêu thiết kế, không phải lời bảo đảm. Stablecoin có thể **mất neo** khi:

- Thị trường nghi ngờ khoản dự trữ.
- Đơn vị phát hành gặp vấn đề pháp lý hoặc ngân hàng giữ dự trữ gặp sự cố.
- Cơ chế thuật toán rơi vào vòng xoáy bán tháo.

## Lưu ý khi dùng

1. Ưu tiên stablecoin có quy mô lớn, minh bạch dự trữ và lịch sử giữ neo tốt.
2. Đừng để toàn bộ tài sản vào một stablecoin duy nhất.
3. Cẩn trọng với nơi hứa trả lãi rất cao cho stablecoin; lợi suất cao luôn đi kèm rủi ro cao.

> *Nội dung chỉ nhằm mục đích giáo dục, không phải lời khuyên đầu tư.*
$md$,
       'BEGINNER', 6, 'PUBLISHED', 1, NULL, NULL,
       TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00'
  FROM academy_topic WHERE slug = 'defi-nft-stablecoin';

INSERT INTO academy_article (article_id, topic_id, slug, title, summary, body, article_level, reading_minutes,
                             article_status, sort_order, path_order, app_link, published_at, created_at, updated_at)
SELECT gen_random_uuid(), topic_id, 'defi-co-ban', 'DeFi cơ bản: DEX, cho vay và thanh khoản',
       'DeFi là các dịch vụ tài chính chạy bằng hợp đồng thông minh, không cần ngân hàng hay sàn trung gian. Bài này giới thiệu sàn phi tập trung, pool thanh khoản, cho vay và những rủi ro riêng của DeFi.',
       $md$## DeFi là gì?

DeFi (tài chính phi tập trung) là tập hợp các ứng dụng tài chính chạy trên blockchain: đổi token, cho vay, đi vay, gửi tiết kiệm... Thay cho nhân viên ngân hàng là **hợp đồng thông minh** — mã nguồn công khai, ai cũng có thể dùng chỉ với một chiếc ví.

## Sàn phi tập trung và AMM

Nhiều DEX không dùng sổ lệnh mà dùng **nhà tạo lập thị trường tự động (AMM)**:

- Người dùng gửi một cặp token vào **pool thanh khoản**, ví dụ ETH và USDC.
- Giá được tính tự động từ tỷ lệ hai token trong pool.
- Ai muốn đổi token thì giao dịch với pool, trả một khoản phí nhỏ; phí này chia cho những người cung cấp thanh khoản.

## Tổn thất tạm thời

Người cung cấp thanh khoản đối mặt với **tổn thất tạm thời** (impermanent loss): khi giá hai token trong pool lệch nhau nhiều, giá trị phần bạn rút ra có thể thấp hơn so với việc cứ giữ nguyên hai token trong ví. Phí thu được có thể bù đắp hoặc không.

## Cho vay và đi vay

Trên giao thức cho vay, người gửi token nhận lãi, còn người vay phải **thế chấp vượt mức**. Khi giá tài sản thế chấp giảm và tỷ lệ an toàn chạm ngưỡng, vị thế bị **thanh lý** tự động — tương tự giá thanh lý trong futures.

## Còn NFT?

NFT (token không thể thay thế) là token đại diện cho một thứ **duy nhất**: một tác phẩm số, một vật phẩm trò chơi, một vé sự kiện. Khác với coin, mỗi NFT không đổi ngang được cho nhau. Giá trị của NFT phụ thuộc hoàn toàn vào nhu cầu, và thanh khoản thường rất thấp.

## Rủi ro riêng của DeFi

- **Lỗi hợp đồng thông minh**: một lỗ hổng trong mã có thể bị khai thác, rút sạch pool.
- **Rug pull**: đội ngũ ẩn danh rút thanh khoản và biến mất.
- **Ký nhầm quyền chi tiêu** cho hợp đồng độc hại.
- **Lợi suất ảo**: APY rất cao thường được trả bằng token mới in, giá có thể giảm nhanh hơn lãi bạn nhận.

Không có bộ phận hỗ trợ khách hàng nào trong DeFi. Hãy thử với số tiền nhỏ và chỉ dùng giao thức đã hoạt động lâu, được kiểm toán.

> *Nội dung chỉ nhằm mục đích giáo dục, không phải lời khuyên đầu tư.*
$md$,
       'INTERMEDIATE', 7, 'PUBLISHED', 2, NULL, NULL,
       TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00'
  FROM academy_topic WHERE slug = 'defi-nft-stablecoin';

-- ---------------------------------------------------------------------------------------------------------------
-- Glossary
-- ---------------------------------------------------------------------------------------------------------------

INSERT INTO academy_term (term_id, slug, term, definition, article_id, created_at, updated_at)
SELECT gen_random_uuid(), v.slug, v.term, v.definition, a.article_id,
       TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00'
  FROM (VALUES
    ('altcoin', 'Altcoin', 'Mọi đồng tiền mã hóa không phải Bitcoin.', 'bitcoin-ethereum-va-altcoin'),
    ('ath', 'ATH (All-Time High)', 'Mức giá cao nhất từ trước đến nay của một tài sản.', NULL),
    ('bear-market', 'Bear market', 'Thị trường giá xuống kéo dài, tâm lý chung bi quan.', NULL),
    ('bull-market', 'Bull market', 'Thị trường giá lên kéo dài, tâm lý chung lạc quan.', NULL),
    ('bitcoin', 'Bitcoin (BTC)', 'Đồng tiền mã hóa đầu tiên, ra đời năm 2009, nguồn cung tối đa 21 triệu.', 'bitcoin-ethereum-va-altcoin'),
    ('blockchain', 'Blockchain', 'Sổ cái phân tán gồm các khối dữ liệu nối với nhau bằng mã băm, do nhiều máy tính cùng lưu giữ.', 'blockchain-hoat-dong-the-nao'),
    ('block-explorer', 'Block explorer', 'Trang web cho phép tra cứu mọi giao dịch, địa chỉ và khối trên một blockchain công khai.', 'blockchain-hoat-dong-the-nao'),
    ('bridge', 'Bridge (cầu nối)', 'Giao thức chuyển tài sản giữa hai blockchain khác nhau.', 'phi-gas-va-layer-2'),
    ('cex', 'CEX', 'Sàn giao dịch tập trung, do một công ty vận hành và giữ tài sản thay khách hàng.', 'san-giao-dich-cap-giao-dich-so-lenh'),
    ('dex', 'DEX', 'Sàn giao dịch phi tập trung, chạy bằng hợp đồng thông minh, giao dịch trực tiếp từ ví.', 'defi-co-ban'),
    ('defi', 'DeFi', 'Tài chính phi tập trung: các dịch vụ đổi, vay, cho vay chạy bằng hợp đồng thông minh.', 'defi-co-ban'),
    ('doji', 'Doji', 'Cây nến có giá mở và giá đóng gần như bằng nhau, thể hiện sự lưỡng lự.', 'doc-bieu-do-nen'),
    ('don-bay', 'Đòn bẩy (leverage)', 'Dùng ký quỹ nhỏ để mở vị thế lớn hơn nhiều lần; phóng đại cả lời lẫn lỗ.', 'don-bay-ky-quy-gia-thanh-ly'),
    ('dyor', 'DYOR', 'Viết tắt của "Do Your Own Research": tự tìm hiểu trước khi quyết định.', NULL),
    ('ema', 'EMA', 'Đường trung bình động hàm mũ, đặt trọng số cao hơn cho giá gần đây.', 'ema-rsi-macd'),
    ('ethereum', 'Ethereum (ETH)', 'Blockchain hỗ trợ hợp đồng thông minh; ETH dùng để trả phí gas.', 'bitcoin-ethereum-va-altcoin'),
    ('fdv', 'FDV', 'Vốn hóa pha loãng hoàn toàn: giá nhân với nguồn cung tối đa hoặc tổng cung.', 'von-hoa-nguon-cung-tokenomics'),
    ('fomo', 'FOMO', 'Nỗi sợ bỏ lỡ, khiến người giao dịch mua vội khi giá đã tăng xa.', 'tam-ly-giao-dich-va-nhat-ky'),
    ('fud', 'FUD', 'Sợ hãi, bất định và nghi ngờ; thường do tin đồn tiêu cực lan truyền.', 'tam-ly-giao-dich-va-nhat-ky'),
    ('funding-rate', 'Funding rate', 'Khoản thanh toán định kỳ giữa phe long và short để giữ giá hợp đồng vĩnh cửu sát giá spot.', 'funding-rate-open-interest'),
    ('futures', 'Futures', 'Hợp đồng mua bán theo giá một tài sản mà không cần sở hữu tài sản đó.', 'hop-dong-vinh-cuu-la-gi'),
    ('gas', 'Gas', 'Đơn vị đo lượng tính toán của một thao tác trên blockchain; phí gas trả bằng đồng gốc của mạng.', 'phi-gas-va-layer-2'),
    ('halving', 'Halving', 'Sự kiện phần thưởng khối của Bitcoin giảm một nửa, khoảng bốn năm một lần.', 'bitcoin-ethereum-va-altcoin'),
    ('hash', 'Hash (mã băm)', 'Chuỗi ký tự cố định sinh ra từ dữ liệu; dữ liệu đổi một chút thì mã băm đổi hoàn toàn.', 'blockchain-hoat-dong-the-nao'),
    ('impermanent-loss', 'Impermanent loss', 'Tổn thất tạm thời của người cung cấp thanh khoản khi giá hai token trong pool lệch nhau.', 'defi-co-ban'),
    ('ky-quy', 'Ký quỹ (margin)', 'Khoản tiền đặt làm bảo đảm để mở và duy trì vị thế có đòn bẩy.', 'hop-dong-vinh-cuu-la-gi'),
    ('ky-quy-cheo', 'Ký quỹ chéo (cross)', 'Chế độ mọi vị thế dùng chung số dư tài khoản làm ký quỹ.', 'don-bay-ky-quy-gia-thanh-ly'),
    ('ky-quy-co-lap', 'Ký quỹ cô lập (isolated)', 'Chế độ mỗi vị thế chỉ dùng phần ký quỹ riêng của nó.', 'don-bay-ky-quy-gia-thanh-ly'),
    ('kyc', 'KYC', 'Quy trình xác minh danh tính khách hàng tại sàn hoặc dịch vụ tài chính.', NULL),
    ('layer-2', 'Layer 2', 'Mạng xây trên một blockchain gốc để xử lý giao dịch nhanh và rẻ hơn.', 'phi-gas-va-layer-2'),
    ('lenh-limit', 'Lệnh limit', 'Lệnh chỉ khớp ở mức giá bạn đặt hoặc tốt hơn.', 'lenh-market-limit-stop'),
    ('lenh-market', 'Lệnh market', 'Lệnh khớp ngay theo giá tốt nhất đang có trong sổ lệnh.', 'lenh-market-limit-stop'),
    ('lenh-stop', 'Lệnh stop', 'Lệnh chỉ được gửi vào thị trường khi giá chạm giá kích hoạt.', 'lenh-market-limit-stop'),
    ('long', 'Long', 'Vị thế kiếm lời khi giá tăng.', 'hop-dong-vinh-cuu-la-gi'),
    ('macd', 'MACD', 'Chỉ báo động lượng dựng từ chênh lệch giữa hai đường EMA và đường tín hiệu của nó.', 'ema-rsi-macd'),
    ('maker-taker', 'Maker / Taker', 'Maker đặt lệnh chờ góp thanh khoản; taker khớp ngay vào lệnh có sẵn.', 'san-giao-dich-cap-giao-dich-so-lenh'),
    ('mark-price', 'Mark price (giá đánh dấu)', 'Giá được làm mượt từ nhiều nguồn, dùng tính lãi lỗ chưa chốt và điều kiện thanh lý.', 'hop-dong-vinh-cuu-la-gi'),
    ('memecoin', 'Memecoin', 'Đồng coin sinh ra từ trào lưu cộng đồng, thường không có công dụng rõ ràng và biến động cực mạnh.', 'bitcoin-ethereum-va-altcoin'),
    ('nft', 'NFT', 'Token không thể thay thế, đại diện cho một thứ duy nhất như tác phẩm số hay vật phẩm trò chơi.', 'defi-co-ban'),
    ('oco', 'OCO', 'Cặp lệnh gồm chốt lời và cắt lỗ; một lệnh khớp thì lệnh còn lại tự hủy.', 'lenh-market-limit-stop'),
    ('open-interest', 'Open interest', 'Tổng giá trị các hợp đồng phái sinh đang mở chưa đóng.', 'funding-rate-open-interest'),
    ('paper-trading', 'Paper trading', 'Giao dịch mô phỏng bằng tiền ảo trên giá thị trường thật.', 'luyen-tap-giao-dich-mo-phong'),
    ('phishing', 'Phishing', 'Lừa đảo bằng trang web, ứng dụng hay tin nhắn giả mạo để lấy thông tin đăng nhập hoặc seed phrase.', 'nhan-dien-lua-dao'),
    ('pos', 'Proof of Stake', 'Cơ chế đồng thuận trong đó người xác thực đặt cọc token để có quyền tạo khối.', 'co-che-dong-thuan-pow-pos'),
    ('pow', 'Proof of Work', 'Cơ chế đồng thuận trong đó thợ đào dùng sức mạnh tính toán để giành quyền tạo khối.', 'co-che-dong-thuan-pow-pos'),
    ('private-key', 'Private key (khóa riêng)', 'Chuỗi bí mật dùng để ký giao dịch; ai có nó là người kiểm soát tài sản trong ví.', 'vi-khoa-rieng-seed-phrase'),
    ('rsi', 'RSI', 'Chỉ số sức mạnh tương đối, dao động 0–100; trên 70 thường gọi là quá mua, dưới 30 là quá bán.', 'ema-rsi-macd'),
    ('rug-pull', 'Rug pull', 'Đội ngũ dự án rút hết thanh khoản hoặc bán tháo token rồi biến mất.', 'nhan-dien-lua-dao'),
    ('rr', 'R:R (Risk/Reward)', 'Tỷ lệ giữa khoản lời mục tiêu và khoản lỗ chấp nhận của một lệnh.', 'quan-ly-von-stop-loss'),
    ('seed-phrase', 'Seed phrase', 'Dãy 12 hoặc 24 từ dùng để khôi phục toàn bộ khóa riêng của một ví.', 'vi-khoa-rieng-seed-phrase'),
    ('short', 'Short', 'Vị thế kiếm lời khi giá giảm.', 'hop-dong-vinh-cuu-la-gi'),
    ('slippage', 'Slippage (trượt giá)', 'Chênh lệch giữa giá bạn dự kiến và giá khớp thực tế.', 'san-giao-dich-cap-giao-dich-so-lenh'),
    ('smart-contract', 'Smart contract', 'Hợp đồng thông minh: chương trình tự chạy trên blockchain theo điều kiện lập trình sẵn.', 'bitcoin-ethereum-va-altcoin'),
    ('spread', 'Spread', 'Khoảng chênh giữa giá mua cao nhất và giá bán thấp nhất trong sổ lệnh.', 'san-giao-dich-cap-giao-dich-so-lenh'),
    ('stablecoin', 'Stablecoin', 'Token được thiết kế để giữ giá ổn định, thường neo 1:1 với USD.', 'stablecoin-la-gi'),
    ('staking', 'Staking', 'Khóa token để góp phần bảo vệ mạng Proof of Stake và nhận phần thưởng.', 'co-che-dong-thuan-pow-pos'),
    ('stop-loss', 'Stop-loss', 'Lệnh cắt lỗ tự động khi giá đi ngược dự tính đến một mức định trước.', 'quan-ly-von-stop-loss'),
    ('take-profit', 'Take profit', 'Lệnh chốt lời khi giá đạt mục tiêu.', 'lenh-market-limit-stop'),
    ('thanh-ly', 'Thanh lý (liquidation)', 'Sàn tự đóng vị thế khi ký quỹ không còn đủ mức duy trì.', 'don-bay-ky-quy-gia-thanh-ly'),
    ('tokenomics', 'Tokenomics', 'Cách một token được phân bổ, mở khóa, phát hành thêm hay đốt bớt và được dùng trong hệ sinh thái.', 'von-hoa-nguon-cung-tokenomics'),
    ('von-hoa', 'Vốn hóa thị trường', 'Giá nhân với nguồn cung đang lưu hành; dùng để so sánh quy mô các đồng coin.', 'von-hoa-nguon-cung-tokenomics'),
    ('vi-lanh', 'Ví lạnh', 'Ví phần cứng giữ khóa riêng ngoại tuyến, khóa không rời thiết bị.', 'vi-khoa-rieng-seed-phrase'),
    ('vi-nong', 'Ví nóng', 'Ví phần mềm kết nối Internet, tiện dùng nhưng dễ bị tấn công hơn ví lạnh.', 'vi-khoa-rieng-seed-phrase'),
    ('whale', 'Whale (cá voi)', 'Cá nhân hoặc tổ chức nắm lượng tài sản đủ lớn để làm giá biến động.', NULL)
  ) AS v (slug, term, definition, article_slug)
  LEFT JOIN academy_article a ON a.slug = v.article_slug;
