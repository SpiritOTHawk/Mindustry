package mindustry.world.blocks.defense.turrets;

import arc.*;
import arc.audio.*;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import arc.util.io.*;
import mindustry.annotations.Annotations.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.entities.*;
import mindustry.entities.Units.*;
import mindustry.entities.bullet.*;
import mindustry.game.EventType.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.logic.*;
import mindustry.type.*;
import mindustry.world.blocks.*;
import mindustry.world.consumers.*;
import mindustry.world.draw.*;
import mindustry.world.meta.*;

import static mindustry.Vars.*;

public class Turret extends ReloadTurret{
    //after being logic-controlled and this amount of time passes, the turret will resume normal AI
    public final static float logicControlCooldown = 60 * 2;

    public final int timerTarget = timers++;
    public int targetInterval = 20;

    public Color heatColor = Pal.turretHeat;
    public Effect shootEffect = Fx.none;
    public Effect smokeEffect = Fx.none;
    public Effect ammoUseEffect = Fx.none;
    public Sound shootSound = Sounds.shoot;

    //general info
    public int maxAmmo = 30;
    public int ammoPerShot = 1;
    public float ammoEjectBack = 1f;
    public float inaccuracy = 0f;
    public float velocityInaccuracy = 0f;
    public float shootWarmupSpeed = 0.1f;
    public int shots = 1;
    public float spread = 4f;
    public float recoilAmount = 1f;
    public float restitution = 0.02f;
    public float cooldown = 0.02f;
    public float coolantUsage = 0.2f;
    public float shootCone = 8f;
    public float shootShake = 0f;
    public float shootLength = -1;
    public float xRand = 0f;
    /** Currently used for artillery only. */
    public float minRange = 0f;
    public float burstSpacing = 0;
    public boolean alternate = false;
    /** If true, this turret will accurately target moving targets with respect to charge time. */
    public boolean accurateDelay = false;
    public boolean targetAir = true;
    public boolean targetGround = true;
    public boolean targetHealing = false;
    public boolean playerControllable = true;
    public boolean displayAmmoMultiplier = true;

    //charging
    public float chargeTime = -1f;
    public int chargeEffects = 5;
    public float chargeMaxDelay = 10f;
    public Effect chargeEffect = Fx.none;
    public Effect chargeBeginEffect = Fx.none;
    public Sound chargeSound = Sounds.none;

    public Sortf unitSort = UnitSorts.closest;

    /** @deprecated loaded in {@link #draw} instead, unused */
    public @Deprecated @Load(value = "@-base", fallback = "block-@size") TextureRegion baseRegion;
    /** @deprecated loaded in {@link #draw} instead, unused */
    public @Deprecated @Load("@-heat") TextureRegion heatRegion;

    public float elevation = -1f;

    public DrawBlock draw = new DrawTurret();

    /** @deprecated use bulletOffset; this will always be zero. **/
    @Deprecated
    protected Vec2 tr = new Vec2();
    /** @deprecated use recoilOffset; this will always be zero. **/
    @Deprecated
    protected Vec2 tr2 = new Vec2();
    /** @deprecated set the draw field instead, this does nothing */
    @Deprecated
    public Cons<TurretBuild> drawer = tile -> {};
    /** @deprecated set the draw field instead, this does nothing */
    @Deprecated
    public Cons<TurretBuild> heatDrawer = tile -> {};

    public Turret(String name){
        super(name);
        liquidCapacity = 20f;
        quickRotate = false;
        outlinedIcon = 1;
    }

    @Override
    public boolean outputsItems(){
        return false;
    }

    @Override
    public void setStats(){
        super.setStats();

        stats.add(Stat.inaccuracy, (int)inaccuracy, StatUnit.degrees);
        stats.add(Stat.reload, 60f / (reloadTime) * (alternate ? 1 : shots), StatUnit.perSecond);
        stats.add(Stat.targetsAir, targetAir);
        stats.add(Stat.targetsGround, targetGround);
        if(ammoPerShot != 1) stats.add(Stat.ammoUse, ammoPerShot, StatUnit.perShot);
    }

    @Override
    public void init(){
        if(acceptCoolant && !consumes.has(ConsumeType.liquid)){
            hasLiquids = true;
            consumes.add(new ConsumeCoolant(coolantUsage)).update(false).boost();
        }
        
        if(shootLength < 0) shootLength = size * tilesize / 2f;
        if(elevation < 0) elevation = size / 2f;

        super.init();
    }

    @Override
    public void load(){
        super.load();

        draw.load(this);
    }

    @Override
    public TextureRegion[] icons(){
        return draw.finalIcons(this);
    }

    @Override
    public void getRegionsToOutline(Seq<TextureRegion> out){
        draw.getRegionsToOutline(this, out);
    }

    public static abstract class AmmoEntry{
        public int amount;

        public abstract BulletType type();
    }

    public class TurretBuild extends ReloadTurretBuild implements ControlBlock{
        //TODO storing these as instance variables is bad design, but it's probably too late to change everything
        /** Turret sprite offset, based on recoil. Updated every frame. */
        public Vec2 recoilOffset = new Vec2();
        /** Turret bullet position offset. Updated every frame. */
        public Vec2 bulletOffset = new Vec2();

        public Seq<AmmoEntry> ammo = new Seq<>();
        public int totalAmmo;
        public float recoil, heat, logicControlTime = -1;
        public float shootWarmup;
        public int shotCounter;
        public boolean logicShooting = false;
        public @Nullable Posc target;
        public Vec2 targetPos = new Vec2();
        public BlockUnitc unit = (BlockUnitc)UnitTypes.block.create(team);
        public boolean wasShooting, charging;

        @Override
        public float warmup(){
            return shootWarmup;
        }

        @Override
        public float drawrot(){
            return rotation - 90;
        }

        @Override
        public boolean shouldConsume(){
            return isShooting();
        }

        @Override
        public boolean canControl(){
            return playerControllable;
        }

        @Override
        public void control(LAccess type, double p1, double p2, double p3, double p4){
            if(type == LAccess.shoot && !unit.isPlayer()){
                targetPos.set(World.unconv((float)p1), World.unconv((float)p2));
                logicControlTime = logicControlCooldown;
                logicShooting = !Mathf.zero(p3);
            }

            super.control(type, p1, p2, p3, p4);
        }

        @Override
        public void control(LAccess type, Object p1, double p2, double p3, double p4){
            if(type == LAccess.shootp && (unit == null || !unit.isPlayer())){
                logicControlTime = logicControlCooldown;
                logicShooting = !Mathf.zero(p2);

                if(p1 instanceof Posc pos){
                    targetPosition(pos);
                }
            }

            super.control(type, p1, p2, p3, p4);
        }

        @Override
        public double sense(LAccess sensor){
            return switch(sensor){
                case ammo -> totalAmmo;
                case ammoCapacity -> maxAmmo;
                case rotation -> rotation;
                case shootX -> World.conv(targetPos.x);
                case shootY -> World.conv(targetPos.y);
                case shooting -> isShooting() ? 1 : 0;
                case progress -> progress();
                default -> super.sense(sensor);
            };
        }

        @Override
        public float progress(){
            return Mathf.clamp(reload / reloadTime);
        }

        public boolean isShooting(){
            return (isControlled() ? unit.isShooting() : logicControlled() ? logicShooting : target != null);
        }

        @Override
        public Unit unit(){
            //make sure stats are correct
            unit.tile(this);
            unit.team(team);
            return (Unit)unit;
        }

        public boolean logicControlled(){
            return logicControlTime > 0;
        }

        public boolean isActive(){
            return (target != null || wasShooting) && enabled;
        }

        public void targetPosition(Posc pos){
            if(!hasAmmo() || pos == null) return;
            BulletType bullet = peekAmmo();

            var offset = Tmp.v1.setZero();

            //when delay is accurate, assume unit has moved by chargeTime already
            if(accurateDelay && pos instanceof Hitboxc h){
                offset.set(h.deltaX(), h.deltaY()).scl(chargeTime / Time.delta);
            }

            targetPos.set(Predict.intercept(this, pos, offset.x, offset.y, bullet.speed <= 0.01f ? 99999999f : bullet.speed));

            if(targetPos.isZero()){
                targetPos.set(pos);
            }
        }

        @Override
        public void draw(){
            draw.drawBase(this);
        }

        @Override
        public void updateTile(){
            if(!validateTarget()) target = null;

            //TODO make it approach instead and add interp curves to parts
            shootWarmup = Mathf.lerpDelta(shootWarmup, isShooting() && cons.canConsume() ? 1f : 0f, shootWarmupSpeed);

            wasShooting = false;

            //TODO do not lerp
            recoil = Mathf.lerpDelta(recoil, 0f, restitution);
            heat = Mathf.lerpDelta(heat, 0f, cooldown);

            unit.tile(this);
            unit.rotation(rotation);
            unit.team(team);
            recoilOffset.trns(rotation, -recoil);
            bulletOffset.trns(rotation, shootLength);

            if(logicControlTime > 0){
                logicControlTime -= Time.delta;
            }

            //turret always reloads regardless of whether it's targeting something
            updateReload();

            if(hasAmmo()){
                if(Float.isNaN(reload)) rotation = 0;

                if(timer(timerTarget, targetInterval)){
                    findTarget();
                }

                if(validateTarget()){
                    boolean canShoot = true;

                    if(isControlled()){ //player behavior
                        targetPos.set(unit.aimX(), unit.aimY());
                        canShoot = unit.isShooting();
                    }else if(logicControlled()){ //logic behavior
                        canShoot = logicShooting;
                    }else{ //default AI behavior
                        targetPosition(target);

                        if(Float.isNaN(rotation)) rotation = 0;
                    }

                    float targetRot = angleTo(targetPos);

                    if(shouldTurn()){
                        turnToTarget(targetRot);
                    }

                    if(Angles.angleDist(rotation, targetRot) < shootCone && canShoot){
                        wasShooting = true;
                        updateShooting();
                    }
                }
            }

            if(acceptCoolant){
                updateCooling();
            }
        }

        @Override
        public void handleLiquid(Building source, Liquid liquid, float amount){
            if(acceptCoolant && liquids.currentAmount() <= 0.001f){
                Events.fire(Trigger.turretCool);
            }

            super.handleLiquid(source, liquid, amount);
        }

        protected boolean validateTarget(){
            return !Units.invalidateTarget(target, canHeal() ? Team.derelict : team, x, y) || isControlled() || logicControlled();
        }

        protected boolean canHeal(){
            return targetHealing && hasAmmo() && peekAmmo().collidesTeam && peekAmmo().healPercent > 0;
        }

        protected void findTarget(){
            if(targetAir && !targetGround){
                target = Units.bestEnemy(team, x, y, range, e -> !e.dead() && !e.isGrounded(), unitSort);
            }else{
                target = Units.bestTarget(team, x, y, range, e -> !e.dead() && (e.isGrounded() || targetAir) && (!e.isGrounded() || targetGround), b -> targetGround, unitSort);

                if(target == null && canHeal()){
                    target = Units.findAllyTile(team, x, y, range, b -> b.damaged() && b != this);
                }
            }
        }

        protected void turnToTarget(float targetRot){
            rotation = Angles.moveToward(rotation, targetRot, rotateSpeed * delta() * baseReloadSpeed());
        }

        public boolean shouldTurn(){
            return !charging;
        }

        /** Consume ammo and return a type. */
        public BulletType useAmmo(){
            if(cheating()) return peekAmmo();

            AmmoEntry entry = ammo.peek();
            entry.amount -= ammoPerShot;
            if(entry.amount <= 0) ammo.pop();
            totalAmmo -= ammoPerShot;
            totalAmmo = Math.max(totalAmmo, 0);
            ejectEffects();
            return entry.type();
        }

        /** @return the ammo type that will be returned if useAmmo is called. */
        public BulletType peekAmmo(){
            return ammo.peek().type();
        }

        /** @return  whether the turret has ammo. */
        public boolean hasAmmo(){
            //skip first entry if it has less than the required amount of ammo
            if(ammo.size >= 2 && ammo.peek().amount < ammoPerShot){
                ammo.pop();
            }
            return ammo.size > 0 && ammo.peek().amount >= ammoPerShot;
        }

        protected void updateReload(){
            float multiplier = hasAmmo() ? peekAmmo().reloadMultiplier : 1f;
            reload += delta() * multiplier * baseReloadSpeed();

            //cap reload for visual reasons
            reload = Math.min(reload, reloadTime);
        }

        protected void updateShooting(){

            if(reload >= reloadTime && !charging){
                BulletType type = peekAmmo();

                shoot(type);

                reload %= reloadTime;
            }
        }

        protected void shoot(BulletType type){

            //when charging is enabled, use the charge shoot pattern
            if(chargeTime > 0){
                useAmmo();

                chargeBeginEffect.at(x + bulletOffset.x, y + bulletOffset.y, rotation);
                chargeSound.at(x + bulletOffset.x, y + bulletOffset.y, 1);

                for(int i = 0; i < chargeEffects; i++){
                    Time.run(Mathf.random(chargeMaxDelay), () -> {
                        if(dead) return;
                        bulletOffset.trns(rotation, shootLength);
                        chargeEffect.at(x + bulletOffset.x, y + bulletOffset.y, rotation);
                    });
                }

                charging = true;

                Time.run(chargeTime, () -> {
                    if(dead) return;
                    bulletOffset.trns(rotation, shootLength);
                    recoil = recoilAmount;
                    heat = 1f;
                    bullet(type, rotation + Mathf.range(inaccuracy + type.inaccuracy));
                    effects();
                    charging = false;
                });

                //when burst spacing is enabled, use the burst pattern
            }else if(burstSpacing > 0.0001f){
                for(int i = 0; i < shots; i++){
                    int ii = i;
                    Time.run(burstSpacing * i, () -> {
                        if(dead || !hasAmmo()) return;
                        bulletOffset.trns(rotation, shootLength, Mathf.range(xRand));
                        bullet(type, rotation + Mathf.range(inaccuracy + type.inaccuracy) + (ii - (int)(shots / 2f)) * spread);
                        effects();
                        useAmmo();
                        recoil = recoilAmount;
                        heat = 1f;
                    });
                }

            }else{
                //otherwise, use the normal shot pattern(s)

                if(alternate){
                    float i = (shotCounter % shots) - (shots-1)/2f;

                    bulletOffset.trns(rotation - 90, spread * i + Mathf.range(xRand), shootLength);
                    bullet(type, rotation + Mathf.range(inaccuracy + type.inaccuracy));
                }else{
                    bulletOffset.trns(rotation, shootLength, Mathf.range(xRand));

                    for(int i = 0; i < shots; i++){
                        bullet(type, rotation + Mathf.range(inaccuracy + type.inaccuracy) + (i - (int)(shots / 2f)) * spread);
                    }
                }

                shotCounter++;

                recoil = recoilAmount;
                heat = 1f;
                effects();
                useAmmo();
            }
        }

        protected void bullet(BulletType type, float angle){
            float lifeScl = type.scaleVelocity ? Mathf.clamp(Mathf.dst(x + bulletOffset.x, y + bulletOffset.y, targetPos.x, targetPos.y) / type.range(), minRange / type.range(), range / type.range()) : 1f;

            type.create(this, team, x + bulletOffset.x, y + bulletOffset.y, angle, 1f + Mathf.range(velocityInaccuracy), lifeScl);
        }

        protected void effects(){
            Effect fshootEffect = shootEffect == Fx.none ? peekAmmo().shootEffect : shootEffect;
            Effect fsmokeEffect = smokeEffect == Fx.none ? peekAmmo().smokeEffect : smokeEffect;

            fshootEffect.at(x + bulletOffset.x, y + bulletOffset.y, rotation);
            fsmokeEffect.at(x + bulletOffset.x, y + bulletOffset.y, rotation);
            shootSound.at(x + bulletOffset.x, y + bulletOffset.y, Mathf.random(0.9f, 1.1f));

            if(shootShake > 0){
                Effect.shake(shootShake, shootShake, this);
            }

            recoil = recoilAmount;
        }

        protected void ejectEffects(){
            if(dead) return;

            //alternate sides when using a double turret
            float scl = (shots == 2 && alternate && shotCounter % 2 == 1 ? -1f : 1f);

            ammoUseEffect.at(x - Angles.trnsx(rotation, ammoEjectBack), y - Angles.trnsy(rotation, ammoEjectBack), rotation * scl);
        }

        @Override
        public void write(Writes write){
            super.write(write);
            write.f(reload);
            write.f(rotation);
        }

        @Override
        public void read(Reads read, byte revision){
            super.read(read, revision);

            if(revision >= 1){
                reload = read.f();
                rotation = read.f();
            }
        }

        @Override
        public byte version(){
            return 1;
        }
    }
}
