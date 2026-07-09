import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

PAGES = 5000          # rounded 9-day heavy-use sample (~4,518 real, within error)
pages = np.linspace(0, PAGES, 300)

# label: (per-page seconds, color, linestyle, linewidth, fill_alpha)
series = [
    ("On-device 14B (est.)",  60.0, "#c62828", "--", 3.4, 0.10),
    ("On-device 7B (est.)",   30.0, "#e08a1e", "--", 3.4, 0.10),
    ("On-device 1.5B (est.)", 10.0, "#8e8e8e", "--", 3.0, 0.10),
    ("Cloud DeepSeek — API only (real)",  2.0, "#2e7d32", "-",  4.6, 0.16),
]

fig, ax = plt.subplots(figsize=(9.8, 4.4), dpi=140)
fig.patch.set_facecolor("white")
ax.set_facecolor("white")

# fills first (back-to-front: biggest area at bottom), then lines on top
for label, s, color, ls, lw, fa in series:
    hours = pages * s / 3600.0
    ax.fill_between(pages, 0, hours, color=color, alpha=fa, lw=0, zorder=1)
for label, s, color, ls, lw, fa in series:
    hours = pages * s / 3600.0
    ax.plot(pages, hours, ls, color=color, lw=lw, label=label, zorder=3,
            solid_capstyle="round")
    h_at = PAGES * s / 3600.0
    ax.plot([PAGES], [h_at], "o", color=color, ms=6, zorder=4)
    ax.annotate(
        f"~{h_at:.1f} h",
        xy=(PAGES, h_at), xytext=(PAGES + 70, h_at),
        va="center", fontsize=12, color=color, fontweight="bold",
        bbox=dict(boxstyle="round,pad=0.15", facecolor="white", edgecolor="none"),
        zorder=5,
    )

fig.suptitle("How long to translate ~5,000 pages", fontsize=15, fontweight="bold", y=1.0)
ax.set_title(
    "translation (LLM / API) step ONLY — the on-device detect / OCR / inpaint pipeline is not counted",
    fontsize=9.5, color="#666666", pad=7,
)
ax.set_xlabel("pages translated", fontsize=12)
ax.set_ylabel("cumulative translation-only time (hours)", fontsize=12)
ax.set_xlim(0, 5500)
ax.set_ylim(0, 90)
ax.tick_params(labelsize=11)
ax.grid(True, color="#eeeeee", lw=0.8, zorder=0)
for spine in ("top", "right"):
    ax.spines[spine].set_visible(False)

# legend in reading order (cloud first)
handles, labels = ax.get_legend_handles_labels()
order = [3, 2, 1, 0]
ax.legend([handles[i] for i in order], [labels[i] for i in order],
          loc="upper left", frameon=False, fontsize=11.5)

fig.text(
    0.5, -0.03,
    "Translation (LLM) step only — detection / OCR / inpaint run on-device in both cases; "
    "cloud translation overlaps the inpaint step, so it is effectively hidden.\n"
    "Cloud = measured (deepseek-v4-flash).  On-device = estimated (dashed, not measured).",
    ha="center", va="top", fontsize=9, color="#777777", linespacing=1.5,
)

plt.tight_layout()
out = "/tmp/claude-1000/-mnt-d-Gits-Yakuyomi/07c86c13-8f51-4422-8648-28988cf441b3/scratchpad/cloud_vs_ondevice_time.png"
plt.savefig(out, facecolor="white", bbox_inches="tight")
print("saved", out)
