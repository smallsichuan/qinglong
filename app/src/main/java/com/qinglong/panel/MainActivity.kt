package com.qinglong.panel

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.qinglong.panel.databinding.ActivityMainBinding
import com.qinglong.panel.service.QingLongForegroundService
import com.qinglong.panel.utils.EnvironmentChecker

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isEnvironmentReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupUI()
        startEnvironmentInitialization()
    }

    private fun setupUI() {
        binding.btnOpenPanel.setOnClickListener {
            if (isEnvironmentReady) {
                openQingLongPanel()
            } else {
                Toast.makeText(this, "环境初始化中，请稍候...", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnCheckUpdate.setOnClickListener {
            startActivity(Intent(this, WebViewActivity::class.java))
        }

        binding.btnSettings.setOnClickListener {
            Toast.makeText(this, "设置功能开发中...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startEnvironmentInitialization() {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnOpenPanel.isEnabled = false
        binding.tvStatus.text = "正在检查环境..."

        EnvironmentChecker.initialize(this) { success, message ->
            runOnUiThread {
                if (success) {
                    environmentReady()
                } else {
                    showError(message)
                }
            }
        }
    }

    private fun environmentReady() {
        isEnvironmentReady = true
        binding.progressBar.visibility = View.GONE
        binding.tvStatus.text = "环境就绪，正在启动青龙..."
        startForegroundService()
    }

    private fun startForegroundService() {
        val serviceIntent = Intent(this, QingLongForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        binding.btnOpenPanel.postDelayed({
            binding.btnOpenPanel.isEnabled = true
            binding.tvStatus.text = "青龙服务已启动"
        }, 1500)
    }

    private fun openQingLongPanel() {
        startActivity(Intent(this, WebViewActivity::class.java))
    }

    private fun showError(message: String) {
        binding.progressBar.visibility = View.GONE
        binding.tvStatus.text = message
        binding.btnOpenPanel.isEnabled = false

        AlertDialog.Builder(this)
            .setTitle("环境初始化失败")
            .setMessage(message)
            .setPositiveButton("重试") { _, _ ->
                startEnvironmentInitialization()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
