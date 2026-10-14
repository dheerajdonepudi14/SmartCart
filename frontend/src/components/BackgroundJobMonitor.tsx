"use client";

import { Client } from "@stomp/stompjs";
import { useEffect, useState } from "react";
import { useAnnounce } from "@/context/AnnouncerContext";
import { API_URL, api, errorMessage, getAccessToken, refreshSession } from "@/lib/api";
import { formatDate } from "@/lib/format";

export type JobStatus = "PENDING" | "PROCESSING" | "COMPLETED" | "FAILED";

export interface BackgroundJob {
  jobId: number;
  orderId: number;
  jobType: "EMAIL" | "INVOICE" | "NOTIFICATION";
  status: JobStatus;
  message: string | null;
  createdAt: string;
  updatedAt: string;
}

/** ws(s)://host/ws-smartcart, derived from the REST base URL (http://host/api). */
export function webSocketUrl(apiUrl: string): string {
  const url = new URL(apiUrl);
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  url.pathname = url.pathname.replace(/\/api\/?$/, "") + "/ws-smartcart";
  return url.toString();
}

/** Newest first; an update replaces the row with the same jobId. */
export function mergeJob(jobs: BackgroundJob[], updated: BackgroundJob): BackgroundJob[] {
  return jobs.some((j) => j.jobId === updated.jobId)
    ? jobs.map((j) => (j.jobId === updated.jobId ? updated : j))
    : [updated, ...jobs];
}

/**
 * Admin view of the background tasks queued after each verified payment. History loads over REST;
 * every PENDING → PROCESSING → COMPLETED/FAILED change then arrives live over STOMP /topic/jobs.
 */
export function BackgroundJobMonitor() {
  const announce = useAnnounce();
  const [jobs, setJobs] = useState<BackgroundJob[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [live, setLive] = useState(false);

  useEffect(() => {
    let active = true;
    api<BackgroundJob[]>("/jobs")
      .then((list) => active && setJobs(list))
      .catch((e) => active && setError(errorMessage(e)));

    const client = new Client({
      brokerURL: webSocketUrl(API_URL),
      reconnectDelay: 5000,
      // The admin JWT goes on the STOMP CONNECT frame; fetch one first if the page has none yet.
      beforeConnect: async () => {
        if (!getAccessToken()) await refreshSession();
        client.connectHeaders = { Authorization: `Bearer ${getAccessToken() ?? ""}` };
      },
      onConnect: () => {
        setLive(true);
        client.subscribe("/topic/jobs", (message) => {
          setJobs((old) => mergeJob(old, JSON.parse(message.body) as BackgroundJob));
        });
      },
      // Usually an expired access token: refresh it so the automatic reconnect succeeds.
      onStompError: () => {
        setLive(false);
        refreshSession();
      },
      onWebSocketClose: () => setLive(false),
    });
    client.activate();

    return () => {
      active = false;
      client.deactivate();
    };
  }, []);

  const retry = async (job: BackgroundJob) => {
    try {
      const updated = await api<BackgroundJob>(`/jobs/${job.jobId}/retry`, { method: "POST" });
      setJobs((old) => mergeJob(old, updated));
      announce(`Retrying ${job.jobType.toLowerCase()} job for order #${job.orderId}`, "info");
    } catch (e) {
      announce(errorMessage(e), "error");
    }
  };

  const openInvoice = async (orderId: number) => {
    // The endpoint needs the bearer token, so fetch it and show it from a blob URL.
    const tab = window.open("", "_blank");
    try {
      const res = await fetch(`${API_URL}/jobs/invoices/${orderId}`, {
        headers: { Authorization: `Bearer ${getAccessToken() ?? ""}` },
        credentials: "include",
      });
      if (!res.ok) throw new Error(`Invoice not available (HTTP ${res.status})`);
      const url = URL.createObjectURL(new Blob([await res.text()], { type: "text/html" }));
      if (tab) tab.location.href = url;
      else window.open(url, "_blank");
    } catch (e) {
      tab?.close();
      announce(errorMessage(e), "error");
    }
  };

  return (
    <section className="job-monitor card" aria-labelledby="jobs-heading">
      <div className="catalog__head">
        <h2 id="jobs-heading">Background Job Monitor</h2>
        <span className="live">
          <span className={`live__dot ${live ? "is-on" : ""}`} aria-hidden="true" />
          {live ? "Live (WebSocket)" : "Connecting…"}
        </span>
      </div>
      {error && <p className="alert alert--error">{error}</p>}
      <div className="table-wrap">
        <table className="table">
          <thead>
            <tr>
              <th scope="col">Job ID</th>
              <th scope="col">Order ID</th>
              <th scope="col">Task</th>
              <th scope="col">Status</th>
              <th scope="col">Message</th>
              <th scope="col">Updated</th>
              <th scope="col">
                <span className="sr-only">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody aria-live="polite">
            {jobs.map((job) => (
              <tr key={job.jobId}>
                <td>{job.jobId}</td>
                <td>#{job.orderId}</td>
                <td>{job.jobType}</td>
                <td>
                  <span className={`job-status job-status--${job.status.toLowerCase()}`}>{job.status}</span>
                </td>
                <td>{job.message}</td>
                <td>{formatDate(job.updatedAt)}</td>
                <td style={{ whiteSpace: "nowrap" }}>
                  {job.status === "FAILED" && (
                    <button type="button" className="link-btn" onClick={() => retry(job)}>
                      Retry<span className="sr-only"> job {job.jobId}</span>
                    </button>
                  )}
                  {job.jobType === "INVOICE" && job.status === "COMPLETED" && (
                    <button type="button" className="link-btn" onClick={() => openInvoice(job.orderId)}>
                      View invoice<span className="sr-only"> for order {job.orderId}</span>
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {jobs.length === 0 && !error && (
          <p className="muted" style={{ padding: 16 }}>
            No background jobs yet. They appear here as soon as a payment is verified.
          </p>
        )}
      </div>
    </section>
  );
}
