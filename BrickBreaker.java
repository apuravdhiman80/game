import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.List;

/**
 * Brick Breaker Deluxe — single file, pure Swing.
 * Controls: A/D or Left/Right to move, SPACE to launch, P to pause, R to restart.
 */
public class BrickBreaker {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("Brick Breaker Deluxe");
            f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            f.setResizable(false);
            GamePanel game = new GamePanel(800, 600);
            f.setContentPane(game);
            f.pack();
            f.setLocationRelativeTo(null);
            f.setVisible(true);
            game.start();
        });
    }

    // ===================== GamePanel (loop + rendering) =====================
    static class GamePanel extends JPanel implements KeyListener, Runnable {
        final int W, H;
        Thread loop;
        boolean running = false, paused = false;

        Paddle paddle;
        List<Ball> balls = new ArrayList<>();
        List<Brick> bricks = new ArrayList<>();
        List<PowerUp> powerUps = new ArrayList<>();
        List<Particle> particles = new ArrayList<>();

        boolean left, right;
        int score = 0, lives = 3, level = 1, highScore = 0;
        boolean waitingToLaunch = true;
        Random rng = new Random();

        long lastNanos = 0;
        final double targetFps = 60.0;
        final double dt = 1.0 / targetFps;

        final Path hiPath = Paths.get(System.getProperty("user.home"), ".brickbreaker_highscore");

        GamePanel(int w, int h) {
            this.W = w; this.H = h;
            setPreferredSize(new Dimension(W, H));
            setFocusable(true);
            addKeyListener(this);
            setBackground(Color.BLACK);
            loadHighScore();
            resetAll();
        }

        void start() {
            if (loop == null || !running) {
                running = true;
                loop = new Thread(this, "game-loop");
                loop.start();
            }
        }

        void stop() {
            running = false;
        }

        void resetAll() {
            score = 0;
            lives = 3;
            level = 1;
            initLevel(level);
            paddle = new Paddle(W / 2.0 - 60, H - 50, 120, 14);
            balls.clear();
            addBallOnPaddle();
            powerUps.clear();
            particles.clear();
            paused = false;
        }

        void addBallOnPaddle() {
            Ball b = new Ball(paddle.x + paddle.w / 2.0, paddle.y - 10, 10);
            b.speed = 300; // px/s
            b.sticky = true;
            balls.add(b);
            waitingToLaunch = true;
        }

        void initLevel(int lvl) {
            bricks.clear();
            // Basic pattern: rows increase HP and points, gaps vary by level
            int cols = 10, rows = 6;
            double padding = 60, top = 60;
            double bw = (W - padding * 2) / cols;
            double bh = 28;

            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    // Make some gaps for variety as level grows
                    boolean hole = (lvl % 2 == 0 && (r + c) % 7 == 0) || (lvl % 3 == 0 && c % 5 == 0 && r % 2 == 0);
                    if (hole) continue;
                    int hp = 1 + (r / 2); // 1..3
                    double x = padding + c * bw + 2;
                    double y = top + r * (bh + 6);
                    bricks.add(new Brick(x, y, bw - 4, bh, hp));
                }
            }
        }

        void loadHighScore() {
            try {
                if (Files.exists(hiPath)) {
                    String s = Files.readString(hiPath).trim();
                    highScore = Integer.parseInt(s);
                }
            } catch (Exception ignored) {}
        }

        void saveHighScore() {
            try {
                if (score > highScore) {
                    Files.writeString(hiPath, String.valueOf(score));
                    highScore = score;
                }
            } catch (Exception ignored) {}
        }

        @Override
        public void run() {
            lastNanos = System.nanoTime();
            double accumulator = 0;
            while (running) {
                long now = System.nanoTime();
                accumulator += (now - lastNanos) / 1e9;
                lastNanos = now;

                while (accumulator >= dt) {
                    update(dt);
                    accumulator -= dt;
                }
                repaint();
                Toolkit.getDefaultToolkit().sync();
                try { Thread.sleep(2); } catch (InterruptedException ignored) {}
            }
        }

        void update(double dt) {
            if (paused) return;

            // Paddle movement
            double speed = 460;
            if (left && !right) paddle.x -= speed * dt;
            if (right && !left) paddle.x += speed * dt;
            paddle.x = clamp(paddle.x, 6, W - paddle.w - 6);

            // Launch ball
            if (waitingToLaunch) {
                for (Ball b : balls) {
                    if (b.sticky) {
                        b.x = paddle.x + paddle.w / 2.0;
                        b.y = paddle.y - b.r;
                    }
                }
            }

            // Update power-ups
            for (PowerUp p : new ArrayList<>(powerUps)) {
                p.y += p.vy * dt;
                if (p.getRect().intersects(paddle.getRect())) {
                    applyPowerUp(p.type);
                    powerUps.remove(p);
                } else if (p.y > H + 30) {
                    powerUps.remove(p);
                }
            }

            // Update balls
            for (Ball b : new ArrayList<>(balls)) {
                if (b.sticky) continue;

                b.x += b.vx * dt;
                b.y += b.vy * dt;

                // Walls
                if (b.x - b.r < 0) { b.x = b.r; b.vx = Math.abs(b.vx); }
                if (b.x + b.r > W) { b.x = W - b.r; b.vx = -Math.abs(b.vx); }
                if (b.y - b.r < 0) { b.y = b.r; b.vy = Math.abs(b.vy); }

                // Bottom (lost)
                if (b.y - b.r > H) {
                    balls.remove(b);
                    if (balls.isEmpty()) {
                        lives--;
                        if (lives >= 0) {
                            addBallOnPaddle();
                        }
                        if (lives < 0) {
                            // Game over — freeze, wait for R
                            saveHighScore();
                            paused = true;
                        }
                    }
                    continue;
                }

                // Paddle collision
                if (b.getCircle().intersects(paddle.getRect())) {
                    double paddleCenter = paddle.x + paddle.w / 2.0;
                    double hitPos = (b.x - paddleCenter) / (paddle.w / 2.0); // -1..1
                    hitPos = clamp(hitPos, -1, 1);
                    double maxAngle = Math.toRadians(65);
                    double angle = hitPos * maxAngle;
                    double speedMag = Math.hypot(b.vx, b.vy);
                    if (speedMag < b.speed) speedMag = b.speed;
                    b.vx = Math.sin(angle) * speedMag;
                    b.vy = -Math.cos(angle) * speedMag;
                    b.y = paddle.y - b.r - 0.1;
                    spawnSparks(b.x, paddle.y, 10);
                }

                // Brick collisions
                for (Brick br : new ArrayList<>(bricks)) {
                    if (!br.alive()) continue;
                    if (circleRectIntersect(b, br)) {
                        // Choose side by minimal overlap
                        double overlapLeft = (b.x + b.r) - br.x;
                        double overlapRight = (br.x + br.w) - (b.x - b.r);
                        double overlapTop = (b.y + b.r) - br.y;
                        double overlapBottom = (br.y + br.h) - (b.y - b.r);
                        double minOverlap = Math.min(Math.min(overlapLeft, overlapRight), Math.min(overlapTop, overlapBottom));
                        if (minOverlap == overlapLeft) b.vx = -Math.abs(b.vx);
                        else if (minOverlap == overlapRight) b.vx = Math.abs(b.vx);
                        else if (minOverlap == overlapTop) b.vy = -Math.abs(b.vy);
                        else b.vy = Math.abs(b.vy);

                        br.hp--;
                        score += 10;
                        spawnSparks(b.x, b.y, 12);

                        // Random power-up
                        if (!br.alive()) {
                            if (rng.nextDouble() < 0.12) {
                                PowerUpType t = PowerUpType.random(rng);
                                powerUps.add(new PowerUp(br.centerX() - 12, br.centerY(), t));
                            }
                        }
                        break; // prevent multi hits this frame
                    }
                }
            }

            // Particles (sparks)
            for (Iterator<Particle> it = particles.iterator(); it.hasNext(); ) {
                Particle p = it.next();
                p.life -= dt;
                p.x += p.vx * dt;
                p.y += p.vy * dt;
                p.vy += 600 * dt * 0.4; // gravity-ish
                if (p.life <= 0) it.remove();
            }

            // Level cleared?
            boolean anyAlive = false;
            for (Brick b : bricks) if (b.alive()) { anyAlive = true; break; }
            if (!anyAlive) {
                level++;
                initLevel(level);
                // boost difficulty slightly
                for (Ball b : balls) { b.speed *= 1.08; scaleVelocity(b, 1.08); }
                // keep current paddle & balls, add a sticky ball if none
                if (balls.isEmpty()) addBallOnPaddle();
            }
        }

        void applyPowerUp(PowerUpType t) {
            switch (t) {
                case EXPAND -> paddle.w = Math.min(paddle.w + 40, 220);
                case SLOW -> {
                    for (Ball b : balls) { b.speed *= 0.85; scaleVelocity(b, 0.85); }
                }
                case MULTI -> {
                    List<Ball> clones = new ArrayList<>();
                    for (Ball src : balls) {
                        Ball b1 = src.cloneBall();
                        Ball b2 = src.cloneBall();
                        rotateVelocity(b1, Math.toRadians(12));
                        rotateVelocity(b2, Math.toRadians(-12));
                        clones.add(b1); clones.add(b2);
                    }
                    balls.addAll(clones);
                }
                case LIFE -> lives++;
            }
        }

        void rotateVelocity(Ball b, double ang) {
            double sp = Math.hypot(b.vx, b.vy);
            double theta = Math.atan2(b.vx, -b.vy) + ang; // remember vy points down
            b.vx = Math.sin(theta) * sp;
            b.vy = -Math.cos(theta) * sp;
        }

        void scaleVelocity(Ball b, double s) {
            b.vx *= s; b.vy *= s;
        }

        void spawnSparks(double x, double y, int n) {
            for (int i = 0; i < n; i++) {
                double a = rng.nextDouble() * Math.PI * 2;
                double sp = 80 + rng.nextDouble() * 220;
                particles.add(new Particle(x, y, Math.cos(a) * sp, Math.sin(a) * sp, 0.4 + rng.nextDouble() * 0.4));
            }
        }

        // --------- Rendering ----------
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Background gradient
            Paint grad = new GradientPaint(0, 0, new Color(12, 12, 18), 0, H, new Color(25, 25, 40));
            g2.setPaint(grad);
            g2.fillRect(0, 0, W, H);

            // HUD
            g2.setColor(Color.WHITE);
            g2.setFont(getFont().deriveFont(Font.BOLD, 18f));
            g2.drawString("Score: " + score, 16, 24);
            g2.drawString("High: " + Math.max(highScore, score), 16, 48);
            g2.drawString("Level: " + level, W - 120, 24);
            drawLives(g2, lives);

            // Bricks
            for (Brick b : bricks) if (b.alive()) b.draw(g2);

            // Power-ups
            for (PowerUp p : powerUps) p.draw(g2);

            // Paddle & balls
            paddle.draw(g2);
            for (Ball b : balls) b.draw(g2);

            // Particles
            for (Particle p : particles) p.draw(g2);

            // Overlays
            if (paused) {
                g2.setComposite(AlphaComposite.SrcOver.derive(0.7f));
                g2.setColor(Color.BLACK);
                g2.fillRect(0, 0, W, H);
                g2.setComposite(AlphaComposite.SrcOver);
                g2.setColor(Color.WHITE);
                g2.setFont(getFont().deriveFont(Font.BOLD, 36f));
                String msg = (lives < 0) ? "Game Over — Press R" : "Paused — Press P";
                centerText(g2, msg, H / 2);
            } else if (waitingToLaunch) {
                g2.setColor(new Color(255, 255, 255, 170));
                g2.setFont(getFont().deriveFont(Font.BOLD, 22f));
                centerText(g2, "Press SPACE to launch", (int) (paddle.y - 22));
            }

            g2.dispose();
        }

        void drawLives(Graphics2D g2, int lives) {
            int x = W / 2 - 60;
            for (int i = 0; i < Math.max(0, lives + 1); i++) {
                g2.setColor(new Color(255, 90, 90));
                int px = x + i * 20;
                Polygon heart = new Polygon();
                heart.addPoint(px + 8, 16);
                heart.addPoint(px + 16, 8);
                heart.addPoint(px + 24, 16);
                heart.addPoint(px + 16, 28);
                g2.fillPolygon(heart);
            }
        }

        void centerText(Graphics2D g2, String s, int y) {
            FontMetrics fm = g2.getFontMetrics();
            int x = (W - fm.stringWidth(s)) / 2;
            g2.drawString(s, x, y);
        }

        // --------- Input ----------
        @Override public void keyTyped(KeyEvent e) {}

        @Override
        public void keyPressed(KeyEvent e) {
            switch (e.getKeyCode()) {
                case KeyEvent.VK_LEFT, KeyEvent.VK_A -> left = true;
                case KeyEvent.VK_RIGHT, KeyEvent.VK_D -> right = true;
                case KeyEvent.VK_SPACE -> {
                    if (waitingToLaunch) {
                        for (Ball b : balls) if (b.sticky) {
                            b.sticky = false;
                            // launch upward with slight random angle
                            double ang = Math.toRadians(80 + new Random().nextInt(20) - 10);
                            b.vx = Math.cos(ang) * (b.speed * 0.6);
                            b.vy = -Math.sin(ang) * b.speed;
                        }
                        waitingToLaunch = false;
                    }
                }
                case KeyEvent.VK_P -> paused = !paused;
                case KeyEvent.VK_R -> {
                    saveHighScore();
                    resetAll();
                }
            }
        }

        @Override
        public void keyReleased(KeyEvent e) {
            switch (e.getKeyCode()) {
                case KeyEvent.VK_LEFT, KeyEvent.VK_A -> left = false;
                case KeyEvent.VK_RIGHT, KeyEvent.VK_D -> right = false;
            }
        }

        // --------- Math / Collision helpers ----------
        boolean circleRectIntersect(Ball b, Brick r) {
            double cx = clamp(b.x, r.x, r.x + r.w);
            double cy = clamp(b.y, r.y, r.y + r.h);
            double dx = b.x - cx, dy = b.y - cy;
            return dx * dx + dy * dy <= b.r * b.r;
        }

        static double clamp(double v, double lo, double hi) {
            return Math.max(lo, Math.min(hi, v));
        }
    }

    // ===================== Entities =====================
    static class Paddle {
        double x, y, w, h;
        Paddle(double x, double y, double w, double h) { this.x=x; this.y=y; this.w=w; this.h=h; }
        Rectangle2D getRect() { return new Rectangle2D.Double(x, y, w, h); }
        void draw(Graphics2D g2) {
            g2.setColor(new Color(120, 180, 255));
            g2.fill(new RoundRectangle2D.Double(x, y, w, h, 10, 10));
        }
    }

    static class Ball {
        double x, y, vx=0, vy=0, r, speed=320;
        boolean sticky=false;
        Ball(double x, double y, double r) { this.x=x; this.y=y; this.r=r; }
        Ellipse2D getCircle() { return new Ellipse2D.Double(x - r, y - r, r * 2, r * 2); }
        void draw(Graphics2D g2) {
            g2.setColor(new Color(250, 250, 250));
            g2.fill(getCircle());
            g2.setColor(new Color(230, 230, 230));
            g2.fill(new Ellipse2D.Double(x - r*0.5, y - r*0.8, r*0.4, r*0.4));
        }
        Ball cloneBall() {
            Ball b = new Ball(x, y, r);
            b.vx = vx; b.vy = vy; b.speed = speed; b.sticky = false;
            return b;
        }
    }

    static class Brick {
        double x, y, w, h; int hp; // 1..3
        Brick(double x, double y, double w, double h, int hp) { this.x=x; this.y=y; this.w=w; this.h=h; this.hp=hp; }
        boolean alive() { return hp > 0; }
        double centerX(){ return x + w/2.0; }
        double centerY(){ return y + h/2.0; }
        void draw(Graphics2D g2) {
            Color c = switch (hp) {
                case 1 -> new Color(255, 170, 80);
                case 2 -> new Color(255, 110, 110);
                default -> new Color(180, 110, 255);
            };
            g2.setColor(c);
            g2.fill(new RoundRectangle2D.Double(x, y, w, h, 8, 8));
            g2.setColor(new Color(255,255,255,40));
            g2.draw(new RoundRectangle2D.Double(x+1, y+1, w-2, h-2, 8, 8));
        }
        Rectangle2D getRect(){ return new Rectangle2D.Double(x,y,w,h); }
    }

    enum PowerUpType { EXPAND, SLOW, MULTI, LIFE;
        static PowerUpType random(Random r) {
            int i = r.nextInt(values().length);
            return values()[i];
        }
    }

    static class PowerUp {
        double x, y, vy = 140; int w=24, h=14; PowerUpType type;
        PowerUp(double x, double y, PowerUpType t){ this.x=x; this.y=y; this.type=t; }
        Rectangle2D getRect(){ return new Rectangle2D.Double(x, y, w, h); }
        void draw(Graphics2D g2){
            g2.setColor(switch(type){
                case EXPAND -> new Color(120, 200, 255);
                case SLOW   -> new Color(120, 255, 170);
                case MULTI  -> new Color(255, 230, 120);
                case LIFE   -> new Color(255, 120, 160);
            });
            g2.fill(new RoundRectangle2D.Double(x, y, w, h, 6, 6));
            g2.setColor(Color.BLACK);
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 12f));
            String s = switch(type){
                case EXPAND -> "XL";
                case SLOW   -> "SN";
                case MULTI  -> "x3";
                case LIFE   -> "+1";
            };
            g2.drawString(s, (int)(x+6), (int)(y+11));
        }
    }

    static class Particle {
        double x,y,vx,vy,life;
        Particle(double x,double y,double vx,double vy,double life){ this.x=x; this.y=y; this.vx=vx; this.vy=vy; this.life=life; }
        void draw(Graphics2D g2){
            int a = (int)(Math.max(0, Math.min(1, life)) * 200 + 30);
            g2.setColor(new Color(255, 240, 200, a));
            g2.fill(new Ellipse2D.Double(x-2, y-2, 4, 4));
        }
    }
}
