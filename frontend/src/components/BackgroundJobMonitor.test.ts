import { describe, expect, it } from "vitest";
import { mergeJob, webSocketUrl, type BackgroundJob } from "./BackgroundJobMonitor";

const job = (jobId: number, status: BackgroundJob["status"]): BackgroundJob => ({
  jobId,
  orderId: 7,
  jobType: "EMAIL",
  status,
  message: null,
  createdAt: "2026-10-06T10:00:00Z",
  updatedAt: "2026-10-06T10:00:00Z",
});

describe("BackgroundJobMonitor helpers", () => {
  it("derives the STOMP endpoint from the API URL", () => {
    expect(webSocketUrl("http://localhost:8100/api")).toBe("ws://localhost:8100/ws-smartcart");
    expect(webSocketUrl("https://shop.example.com/api/")).toBe("wss://shop.example.com/ws-smartcart");
  });

  it("updates an existing job in place and prepends new ones", () => {
    const list = [job(2, "PENDING"), job(1, "COMPLETED")];
    expect(mergeJob(list, job(2, "PROCESSING")).map((j) => [j.jobId, j.status])).toEqual([
      [2, "PROCESSING"],
      [1, "COMPLETED"],
    ]);
    expect(mergeJob(list, job(3, "PENDING")).map((j) => j.jobId)).toEqual([3, 2, 1]);
  });
});
