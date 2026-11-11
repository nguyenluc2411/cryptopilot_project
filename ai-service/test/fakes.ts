import type { ChatRequest, GenerateRequest, GenerateResult, LlmProvider } from "../src/llm/provider.js";

type Answer<T> = (request: T) => GenerateResult | Promise<GenerateResult>;

const unexpected = (): never => {
  throw new Error("must not be called");
};

/** A provider that answers from functions and remembers what it was asked, for JSON and for chat. */
export class FakeProvider implements LlmProvider {
  readonly name = "fake";
  readonly requests: GenerateRequest[] = [];
  readonly chats: ChatRequest[] = [];

  constructor(
    private readonly answer: Answer<GenerateRequest> = unexpected,
    private readonly chatAnswer: Answer<ChatRequest> = unexpected,
  ) {}

  async generateJson(request: GenerateRequest): Promise<GenerateResult> {
    this.requests.push(request);
    return this.answer(request);
  }

  async generateText(request: ChatRequest): Promise<GenerateResult> {
    this.chats.push(request);
    return this.chatAnswer(request);
  }
}
